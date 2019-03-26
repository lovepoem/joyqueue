package com.jd.journalq.broker.consumer;

import com.google.common.collect.Lists;
import com.jd.journalq.broker.archive.ArchiveManager;
import com.jd.journalq.broker.archive.ConsumeArchiveService;
import com.jd.journalq.broker.buffer.Serializer;
import com.jd.journalq.broker.cluster.ClusterManager;
import com.jd.journalq.domain.Partition;
import com.jd.journalq.domain.QosLevel;
import com.jd.journalq.domain.TopicName;
import com.jd.journalq.exception.JMQCode;
import com.jd.journalq.exception.JMQException;
import com.jd.journalq.message.MessageLocation;
import com.jd.journalq.network.session.Connection;
import com.jd.journalq.network.session.Consumer;
import com.jd.journalq.broker.consumer.filter.FilterCallback;
import com.jd.journalq.broker.consumer.model.PullResult;
import com.jd.journalq.broker.consumer.position.PositionManager;
import com.jd.journalq.server.retry.api.MessageRetry;
import com.jd.journalq.server.retry.model.RetryMessageModel;
import com.jd.journalq.store.PartitionGroupStore;
import com.jd.journalq.store.PositionOverflowException;
import com.jd.journalq.store.PositionUnderflowException;
import com.jd.journalq.store.ReadResult;
import com.jd.journalq.store.StoreService;
import com.jd.journalq.toolkit.lang.Preconditions;
import com.jd.journalq.toolkit.network.IpUtil;
import com.jd.journalq.toolkit.service.Service;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认的消息消费方式，线程安全
 * <p>
 * Created by chengzhiliang on 2018/8/16.
 */
class PartitionConsumption extends Service {

    private final Logger logger = LoggerFactory.getLogger(PartitionConsumption.class);

    // 分区管理
    private PartitionManager partitionManager;
    // 存储服务
    private StoreService storeService;
    // 集群管理
    private ClusterManager clusterManager;
    // 消费位置管理
    private PositionManager positionManager;
    // 延迟消费处理器
    private DelayHandler delayHandler = new DelayHandler();
    // 消费过滤帮助类
    private FilterMessageSupport filterMessageSupport;
    // 尝试管理
    private MessageRetry messageRetry;
    // 消费归档
    private ArchiveManager archiveManager;

    public PartitionConsumption(ClusterManager clusterManager, StoreService storeService, PartitionManager partitionManager,
                                PositionManager positionManager, MessageRetry messageRetry,
                                FilterMessageSupport filterMessageSupport, ArchiveManager archiveManager) {
        this.clusterManager = clusterManager;
        this.storeService = storeService;
        this.partitionManager = partitionManager;
        this.positionManager = positionManager;
        this.messageRetry = messageRetry;
        this.filterMessageSupport = filterMessageSupport;
        this.archiveManager = archiveManager;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        logger.info("PartitionConsumption is started.");
    }

    @Override
    protected void doStop() {
        super.doStop();
        logger.info("PartitionConsumption is stopped.");
    }

    /**
     * 读取消息
     * <p>
     * 消费重试消息
     * <br>
     * 接着消费高优先级消息
     * <br>
     * 最后尝试从分区读
     *
     * @param consumer    消费者信息
     * @param count       消息条数
     * @param accessTimes 访问次数用于均匀读取每个分区
     * @return 读取的消息
     */
    protected PullResult getMessage(Consumer consumer, int count, long ackTimeout, long accessTimes) throws JMQException {
        PullResult pullResult = new PullResult(consumer, (short) -1, new ArrayList<>(0));

        if (partitionManager.isRetry(consumer)) {
            // 消费待重试的消息
            List<RetryMessageModel> retryMsgList = messageRetry.getRetry(consumer.getTopic(), consumer.getApp(), (short) 1, 0l);
            if (CollectionUtils.isNotEmpty(retryMsgList) && retryMsgList.size() > 0) {
                pullResult = brokerMessage2PullResult(consumer, retryMsgList);
                // 读到重试消息，增加下次读到重试队列的概率（优先处理重试消息）
                partitionManager.increaseRetryProbability(consumer);
            } else {
                // 读不到重试消息，则降低下次读重试队列的概率
                partitionManager.decreaseRetryProbability(consumer);
            }
        }
        List<Short> priorityPartitionList = partitionManager.getPriorityPartition(TopicName.parse(consumer.getTopic()));
        if (pullResult.getBuffers().size() < 1 && priorityPartitionList.size() > 0) {
            // 高优先级分区消费
            pullResult = getFromPartition(consumer, priorityPartitionList, count, ackTimeout, accessTimes);
        }
        if (pullResult.getBuffers().size() < 1) {
            // 消费普通分区消息
            List<Short> partitionList = clusterManager.getMasterPartitionList(TopicName.parse(consumer.getTopic()));
            pullResult = getFromPartition(consumer, partitionList, count, ackTimeout, accessTimes);
        }

        return pullResult;
    }

    /**
     * BrokerMessage 转成 PullResult
     *
     * @param consumer
     * @param msgList
     * @return
     */
    private PullResult brokerMessage2PullResult(Consumer consumer, List<RetryMessageModel> msgList) throws JMQException {
        List<ByteBuffer> resultList = new ArrayList<>(msgList.size());
        for (RetryMessageModel message : msgList) {
            ByteBuffer wrap = ByteBuffer.wrap(message.getBrokerMessage());
            Serializer.setPartition(wrap, Partition.RETRY_PARTITION_ID);
            Serializer.setIndex(wrap, message.getIndex());
            resultList.add(wrap);
        }

        PullResult pullResult = new PullResult(consumer, Partition.RETRY_PARTITION_ID, resultList);
        return pullResult;
    }

    /**
     * 从本地磁盘分区消费消息
     *
     * @param consumer    消费者信息
     * @param count       消息条数
     * @param ackTimeout  应答超时
     * @param accessTimes 访问次数
     * @return
     */
    private PullResult getFromPartition(Consumer consumer, List<Short> partitionList, int count, long ackTimeout, long accessTimes) throws JMQException {
        int partitionSize = partitionList.size();
        int listIndex = -1;
        for (int i = 0; i < partitionSize; i++) {
            listIndex = partitionManager.selectPartitionIndex(partitionSize, listIndex, accessTimes);
            short partition = partitionList.get(listIndex);
            PullResult pullResult = getMessage4Sequence(consumer, partition, count, ackTimeout);
            int pullMsgCount = pullResult.getBuffers().size();
            if (pullMsgCount > 0) {
                // 增加拉取序号
                positionManager.increaseMsgPullIndex(TopicName.parse(consumer.getTopic()), consumer.getApp(), partition, pullMsgCount);
                return pullResult;
            }
            listIndex++;
        }
        return new PullResult(consumer, (short) -1, new ArrayList<>(0));
    }

    /**
     * 指定分区读取消息，kafka使用
     *
     * @param consumer  消费者信息
     * @param group     partitionGroup
     * @param count     消息条数
     * @param partition 消费分区
     * @return 读取的消息
     */
    protected PullResult getMsgByPartitionAndIndex(Consumer consumer, int group, short partition, long index, int count) throws JMQException, IOException {
        PullResult pullResult = new PullResult(consumer, (short) -1, new ArrayList<>(0));
        try {
            PartitionGroupStore store = storeService.getStore(consumer.getTopic(), group, QosLevel.PERSISTENCE);
            ReadResult readRst = store.read(partition, index, count, Long.MAX_VALUE);
            if (readRst.getCode() == JMQCode.SUCCESS) {
                ByteBuffer[] byteBufferArr = readRst.getMessages();
                if (byteBufferArr == null) {
                    // 没有拉到消息直接返回
                    return pullResult;
                }

                List<ByteBuffer> byteBuffers = Lists.newArrayList(byteBufferArr);
                if (StringUtils.isNotEmpty(consumer.getApp())) {
                    com.jd.journalq.domain.Consumer consumerConfig = clusterManager.getConsumer(TopicName.parse(consumer.getTopic()), consumer.getApp());

                    // 过滤消息
                    byteBuffers = filterMessageSupport.filter(consumerConfig, byteBuffers, new FilterCallbackImpl(consumer));

                    // 开启延迟消费，过滤未到消费时间的消息
                    byteBuffers = delayHandler.handle(consumerConfig.getConsumerPolicy(), byteBuffers);
                }

                pullResult = new PullResult(consumer, partition, byteBuffers);
            } else {
                logger.error("read message error, error code[{}]", readRst.getCode());
            }
        } catch (PositionOverflowException overflow) {
            logger.debug("PositionOverflow,topic:{},partition:{},index:{}", consumer.getTopic(), partition, index);
            if (overflow.getPosition() != overflow.getRight()) {
                pullResult.setJmqCode(JMQCode.SE_INDEX_OVERFLOW);
            }
        } catch (PositionUnderflowException underflow) {
            logger.debug("PositionUnderflow,topic:{},partition:{},index:{}", consumer.getTopic(), partition, index);
            pullResult.setJmqCode(JMQCode.SE_INDEX_UNDERFLOW);
        }

        return pullResult;
    }

    /**
     * 指定分区读取消息，kafka使用
     *
     * @param consumer  消费者信息
     * @param count     消息条数
     * @param partition 消费分区
     * @return 读取的消息
     */
    protected PullResult getMsgByPartitionAndIndex(Consumer consumer, short partition, long index, int count) throws IOException, JMQException {
        Integer group = partitionManager.getGroupByPartition(TopicName.parse(consumer.getTopic()), partition);
        Preconditions.checkArgument(group != null && group >= 0, "找不到主题[" + consumer.getTopic() + "]" + ",分区[" + partition + "]的分区组");
        return getMsgByPartitionAndIndex(consumer, group, partition, index, count);
    }

    /**
     * 指定分组、分区读取消息，顺序消费可直接调用
     *
     * @param consumer   消费者信息
     * @param count      消息条数
     * @param partition  消费分区
     * @param ackTimeout 应答超时时间
     * @return 读取的消息
     */
    protected PullResult getMessage4Sequence(Consumer consumer, short partition, int count, long ackTimeout) throws JMQException {
        // 初始化默认
        PullResult pullResult = new PullResult(consumer, (short) -1, new ArrayList<>(0));
        if (partitionManager.tryOccupyPartition(consumer, partition, ackTimeout)) {
            int partitionGroup = clusterManager.getPartitionGroupId(TopicName.parse(consumer.getTopic()), partition);
            long index = positionManager.getLastMsgAckIndex(TopicName.parse(consumer.getTopic()), consumer.getApp(), partition);
            try {
                ByteBuffer[] byteBuffers = readMessages(consumer, partitionGroup, partition, index, count);
                if (byteBuffers == null) {
                    // 如果没有拿到消息，则释放占用
                    partitionManager.releasePartition(consumer, partition);
                    return pullResult;
                }

                List<ByteBuffer> rByteBufferList = Lists.newArrayList(byteBuffers);
                com.jd.journalq.domain.Consumer consumerConfig = clusterManager.getConsumer(TopicName.parse(consumer.getTopic()), consumer.getApp());

                // 过滤消息
                rByteBufferList = filterMessageSupport.filter(consumerConfig, rByteBufferList, new FilterCallbackImpl(consumer));

                // 开启延迟消费，过滤未到消费时间的消息
                rByteBufferList = delayHandler.handle(consumerConfig.getConsumerPolicy(), rByteBufferList);

                // 判断是否释放占用
                if (rByteBufferList != null && rByteBufferList.size() == 0 ) {
                    // 读不到消息释放占用
                    partitionManager.releasePartition(consumer, partition);
                }
                pullResult = new PullResult(consumer, partition, rByteBufferList);
            } catch (Exception ex) {
                // 出现异常释放分区占用
                partitionManager.releasePartition(consumer, partition);

                if (ex instanceof PositionOverflowException) {
                    pullResult.setJmqCode(JMQCode.SE_INDEX_OVERFLOW);
                } else if (ex instanceof PositionUnderflowException) {
                    pullResult.setJmqCode(JMQCode.SE_INDEX_UNDERFLOW);
                } else {
                    logger.error("get message error, consumer: {}, partition: {}", consumer, partition, ex);
                }
            }
        }
        return pullResult;
    }

    class FilterCallbackImpl implements FilterCallback {

        private Consumer consumer;

        public FilterCallbackImpl(Consumer consumer) {
            this.consumer = consumer;
        }

        @Override
        public void callback(List<ByteBuffer> byteBuffers) throws JMQException {
            innerAcknowledge(consumer, byteBuffers);
        }
    }

    /**
     * 指定分组、分区、序号读取消息
     * <br>
     * 处理分区占用<br>
     * 延迟消费问题
     *
     * @param consumer       消费者信息
     * @param partitionGroup 消费分FetchClusterAckCodec区所在分组
     * @param partition      消费分区
     * @param index          消息序号
     * @param count          消息条数
     * @return 读取的消息
     */
    private ByteBuffer[] readMessages(Consumer consumer, int partitionGroup, short partition, long index, int count) throws IOException {
        PartitionGroupStore store = storeService.getStore(consumer.getTopic(), partitionGroup);
        try {
            ReadResult readRst = store.read(partition, index, count, Long.MAX_VALUE);
            if (readRst.getCode() == JMQCode.SUCCESS) {
                return readRst.getMessages();
            } else {
                logger.error("read message error, error code[{}]", readRst.getCode());
            }
        } catch (PositionOverflowException overflow) {
            logger.debug("PositionOverflow,topic:{},partition:{},index:{}", consumer.getTopic(), partition, index);
            throw overflow;
        } catch (PositionUnderflowException underflow) {
            logger.debug("PositionUnderflow,topic:{},partition:{},index:{}", consumer.getTopic(), partition, index);
            throw underflow;
        }
        return null;
    }

    /**
     * broker内部应答的APP名称
     */
    private final String innerAppPrefix = "innerFilter@";
    /**
     * 内部应答
     *
     * @param consumer    消费者
     * @param inValidList 无效消息集合
     * @throws JMQException
     */
    private void innerAcknowledge(Consumer consumer, List<ByteBuffer> inValidList) throws JMQException{
        if (inValidList == null) {
            return;
        }
        MessageLocation[] messageLocations = convertMessageLocation(consumer.getTopic(), inValidList);
        acknowledge(messageLocations, consumer, true);
        archiveIfNecessary(messageLocations);
    }

    private void archiveIfNecessary(MessageLocation[] messageLocations) throws JMQException {
        ConsumeArchiveService archiveService;

        if (archiveManager == null || (archiveService = archiveManager.getConsumeArchiveService()) == null) {
            return;
        }

        // 归档需要用到 address、app
        Connection connection = new Connection();
        try {
            connection.setAddress(IpUtil.toByte(new InetSocketAddress(IpUtil.getLocalIp(), 50088)));
        } catch (Exception ex) {
            // 如果获取本机IP为空，则添加0长度的byte数组
            connection.setAddress(new byte[0]);
        }

        connection.setApp(innerAppPrefix + connection.getApp());

        archiveService.appendConsumeLog(connection, messageLocations);
    }

    /**
     * 将消息集合转换为应答位置数组
     *
     * @param topic 主题
     * @param inValidList 有效消息集合
     */
    private MessageLocation[] convertMessageLocation(String topic, List<ByteBuffer> inValidList) {
        MessageLocation[] locations = new MessageLocation[inValidList.size()];
        for (int i = 0; i < inValidList.size(); i++) {
            ByteBuffer buffer = inValidList.get(i);
            short partition = Serializer.readPartition(buffer);
            long index = Serializer.readIndex(buffer);
            locations[i] = new MessageLocation(topic, partition, index);
        }
        return locations;
    }

    /**
     * 应答
     *
     * @param locations    消费位置
     * @param consumer     消费者信息
     * @param isSuccessAck 是否消费成功
     * @return
     * @throws JMQException
     */
    public boolean acknowledge(MessageLocation[] locations, Consumer consumer, boolean isSuccessAck) throws JMQException {
        boolean isSuccess = false;
        if (locations.length < 1) {
            return false;
        }

        String topic = consumer.getTopic();
        String app = consumer.getApp();
        short partition = locations[0].getPartition();
        // 重试应答
        if (partition == Partition.RETRY_PARTITION_ID) {
            return retryAck(topic, app, locations, isSuccessAck);
        }
        long[] indexArr = AcknowledgeSupport.sortMsgLocation(locations);
        if (indexArr != null) {
            long lastMsgAckIndex = positionManager.getLastMsgAckIndex(TopicName.parse(topic), app, partition);
            // 如果应答的序号与之前应答序号连续，则更新应答序号
            if (lastMsgAckIndex == indexArr[0]) {
                long curIndex = indexArr[1];
                // 将当前序号向后移动一位
                long updateMsgAckIndex = curIndex + 1;
                isSuccess = positionManager.updateLastMsgAckIndex(TopicName.parse(topic), app, partition, updateMsgAckIndex);
            } else if (lastMsgAckIndex >= indexArr[1]) {
                isSuccess = true;
            }
        } else {
            throw new JMQException(JMQCode.FW_CONSUMER_ACK_FAIL, "序号不连续或有重复");
        }

        return isSuccess;
    }

    /**
     * 重试应答
     *
     * @param topic
     * @param app
     * @param isSuccess
     * @return
     * @throws JMQException
     */
    private boolean retryAck(String topic, String app, MessageLocation[] locations, boolean isSuccess) {
        Long[] indexArr = new Long[locations.length];
        for (int i = 0; i < locations.length; i++) {
            indexArr[i] = locations[i].getIndex();
        }
        try {
            if (isSuccess) {
                messageRetry.retrySuccess(topic, app, indexArr);
            } else {
                messageRetry.retryError(topic, app, indexArr);
            }
        } catch (JMQException e) {
            logger.error("RetryAck error.", e);
            return false;
        }
        return true;
    }

}