package org.shoper;

import com.daqsoft.log.util.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Created by ShawnShoper on 2017/4/24.
 */
@Component
public class ASSS {
    com.daqsoft.log.util.Logger logger = LogFactory.getLogger(ASSS.class);
    Logger logger1 = LoggerFactory.getLogger(ASSS.class);
    @PostConstruct
    public void ss() throws InterruptedException {
        new Thread(()-> logger.debug("123123")).start();
        logger.info("撒旦");
        logger.debug("撒旦");
        logger.warn("撒旦");
        logger.error("撒旦");
        logger1.warn("warn");
        logger1.debug("debug");
        logger1.error("error");
    }
}
