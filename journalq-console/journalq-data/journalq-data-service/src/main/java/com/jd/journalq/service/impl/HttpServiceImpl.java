package com.jd.journalq.service.impl;

import com.jd.journalq.exception.ServiceException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.jd.journalq.exception.ServiceException.INTERNAL_SERVER_ERROR;


/**
 * Created by wangxiaofei1 on 2018/10/17.
 */
@Service
public class HttpServiceImpl implements HttpService {
    private static final Logger logger = LoggerFactory.getLogger(HttpServiceImpl.class);
    protected static final long DEFAULT_HTTP_CONN_TIME_TO_LIVE = 60;
    protected static final int DEFAULT_HTTP_CONN_MAX_TOTAL = 10;
    /**
     * 上下文信息
     */
    private HttpClientBuilder httpClientBuilder;
    public HttpServiceImpl() {
        clientConnManager = new PoolingHttpClientConnectionManager(DEFAULT_HTTP_CONN_TIME_TO_LIVE, TimeUnit.SECONDS);
        clientConnManager.setMaxTotal(DEFAULT_HTTP_CONN_MAX_TOTAL);
        httpClientBuilder = HttpClientBuilder.create().setConnectionManager(clientConnManager);
    }

    /**
     * 链接管理器
     */
    private PoolingHttpClientConnectionManager clientConnManager;


    private CloseableHttpClient getClient() throws Exception {
        return httpClientBuilder.build();
    }

    @Override
    public CloseableHttpResponse executeRequest(HttpUriRequest request) throws Exception {
        try {
            return getClient().execute(request);
        } catch (IOException e) {
            String errorMsg = String.format("error occurred while communicating with jdos, request = %s", request);
            logger.error(errorMsg, e);
            throw new ServiceException(INTERNAL_SERVER_ERROR, errorMsg);//回滚
        }
    }
}