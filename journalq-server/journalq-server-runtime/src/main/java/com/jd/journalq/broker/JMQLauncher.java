package com.jd.journalq.broker;

import com.jd.ump.profiler.proxy.Profiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

/**
 * jmq加载器
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2018/8/27
 */
public class JMQLauncher {
    protected static final Logger logger = LoggerFactory.getLogger(JMQLauncher.class);
    protected static final String SYSTEM_EVN_FILE = "system.properties";
    protected static final String separator = "\n";

    public static void main(String[] args) throws Exception {
        Profiler.registerJVMInfo("jmq4.0.server.jvm");
        BrokerService brokerService = new BrokerService();
        try {
            Properties sysEvn = new Properties();
            InputStream inputStream = JMQLauncher.class.getClassLoader().getResourceAsStream(SYSTEM_EVN_FILE);
            if (null != inputStream) {
                sysEvn.load(inputStream);
            }
            sysEvn.forEach((k, v) -> System.setProperty(k.toString(), v.toString()));
            brokerService.start();
            logger.info(
                    ">>>" + separator +
                            ">>>       _   __  __    ____  " + separator +
                            ">>>      | | |  \\/  |  / __ \\ " + separator +
                            ">>>      | | | \\  / | | |  | |" + separator +
                            ">>>  _   | | | |\\/| | | |  | |" + separator +
                            ">>> | |__| | | |  | | | |__| |" + separator +
                            ">>> \\______/ |_|  |_| \\__\\__\\/" + separator +
                            ">>>                           ");
            logger.info("JMQLauncher is started");
        } catch (Throwable t) {
            logger.error("JMQLauncher start exception", t);
            brokerService.stop();
            System.exit(-1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    brokerService.stop();
                    logger.info("JMQLauncher stopped");
                } catch (Throwable t) {
                    logger.error("JMQLauncher stop exception", t);
                    System.exit(-1);
                }
            }
        });
    }
}