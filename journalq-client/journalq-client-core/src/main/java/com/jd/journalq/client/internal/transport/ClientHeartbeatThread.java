package com.jd.journalq.client.internal.transport;

import com.jd.journalq.client.internal.transport.config.TransportConfig;
import com.jd.journalq.toolkit.time.SystemClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClientHeartbeatThread
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2019/1/7
 */
public class ClientHeartbeatThread implements Runnable {

    protected static final Logger logger = LoggerFactory.getLogger(ClientHeartbeatThread.class);

    private TransportConfig transportConfig;
    private ClientGroupManager clientGroupManager;

    public ClientHeartbeatThread(TransportConfig transportConfig, ClientGroupManager clientGroupManager) {
        this.transportConfig = transportConfig;
        this.clientGroupManager = clientGroupManager;
    }

    @Override
    public void run() {
        for (ClientGroup clientGroup : clientGroupManager.getGroups()) {
            doHeartbeat(clientGroup);
        }
    }

    protected void doHeartbeat(ClientGroup clientGroup) {
        for (Client client : clientGroup.getClients()) {
            if (client.getLastUseTime() != 0 &&
                    SystemClock.now() - client.getLastUseTime() >= transportConfig.getHeartbeatMaxIdleTime()) {
                doHeartbeat(client);
            }
        }
    }

    protected void doHeartbeat(Client client) {
        try {
            client.heartbeat(transportConfig.getHeartbeatTimeout());
        } catch (Exception e) {
            logger.debug("client heartbeat exception", e);
        }
    }
}