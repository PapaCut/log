package com.daqsoft.log.util.appender;

import com.daqsoft.commons.core.DateUtil;
import com.daqsoft.commons.core.StringUtil;
import com.daqsoft.commons.feign.support.SpringMvcFeign;
import com.daqsoft.commons.responseEntity.DataResponse;
import com.daqsoft.log.api.KafkaConfigApi;
import com.daqsoft.log.core.config.Constans;
import com.daqsoft.log.core.serialize.Business;
import com.daqsoft.log.core.serialize.Log;
import com.daqsoft.log.util.config.FileProperties;
import com.daqsoft.log.util.config.KafkaProperties;
import com.daqsoft.log.util.config.LogProperties;
import com.daqsoft.log.util.constans.FileCap;
import com.daqsoft.log.util.exception.KafkaConnectionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.producer.internals.ErrorLoggingCallback;

import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by ShawnShoper on 2017/5/16.
 * Kafka输出实现,内置提供双端队列,进行存储发送失败的日志列表,以便重发,并存储到文件,以便系统挂掉后,日志丢失
 */
public class KafkaAppender extends Appender {
    KafkaProducer<String, String> producer;
    //文件配置
    FileProperties fileProperties;
    private String topic;
    //kid is key's is ,use to kafka partition
    private String kid;
    //检查服务器状态是否可用
    private AtomicBoolean available = new AtomicBoolean(false);
    //Failed queue
    private LinkedBlockingDeque<Log> failedQueue = new LinkedBlockingDeque();
    private KafkaProperties kafka;

    public KafkaAppender(LogProperties logProperties) {
        super(logProperties);
        this.fileProperties = logProperties.getFileProperties();
        kafka = logProperties.getKafka();
    }

    private Map<String, Object> config = null;

    private void initConnect() {
        KafkaConfigApi target = SpringMvcFeign.target(KafkaConfigApi.class, "http://" + kafka.getKafkaServer());
        //Init kafka...
        DataResponse<Map<String, Object>> kafkaConfig = target.getKafkaConfig(kafka.getKafkaKey(), kafka.getKafkaCert());
        kid = kafka.getKafkaKey();
        if (kafkaConfig.getCode() == 0) {
            config = kafkaConfig.getData();
            this.producer = new KafkaProducer<>(config);
            if (Objects.isNull(config.get("topic")))
                throw new RuntimeException("this topic not exists in server config");
            this.topic = config.get("topic").toString();
            available.compareAndSet(false, true);
        } else {
            throw new KafkaConnectionException(kafkaConfig.getMessage());
        }
    }

    @Override
    public void write(Log log) throws IOException {
        send(log);
    }

    private AtomicBoolean over = new AtomicBoolean(true);

    ObjectMapper mapper = new ObjectMapper();

    private void send(Log log) {
        over.compareAndSet(true, false);
        try {
            String json = mapper.writeValueAsString(log);
            try {
//                producer.
                //Kafka key
                if (available.get()) {
                    ProducerRecord<String, String> producerRecord = new ProducerRecord<>(Objects.isNull(log.getChannel()) ? this.topic : log.getChannel(), 0, kid, json);
                    producer.send(producerRecord, (metadata, exception) -> {
                        if (Objects.nonNull(exception)) {
                            failedQueue.add(log);
                            available.compareAndSet(true, false);
//                            disConnect();
                        }
                    });
                    producer.flush();
//                    available.compareAndSet(false, true);
                }
            } catch (Exception e) {
                e.printStackTrace();
                available.compareAndSet(true, false);
                failedQueue.add(log);
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }finally {
            over.compareAndSet(true,false);
        }
    }

    @Override
    public boolean canDestory() {
        return over.get();
    }

    /**
     * Destroy Kafka produce connection
     * and failed output stream
     */
    @Override
    public void destroy() {
        if (Objects.nonNull(producer)) {
            try {
                producer.close();
            } catch (Throwable ex) {
                ex.printStackTrace();
            } finally {
                this.topic = null;
            }
        }
//        if (Objects.nonNull(backupOutputWrite)) {
//            try {
//                backupOutputWrite.close();
//            } catch (IOException e) {
//                e.printStackTrace();
//            } finally {
//                backupOutputWrite = null;
//            }
//        }
    }


    /**
     * 初始化连接信息并创建连接失败处理以及重连机制
     */
    @Override
    public void init() {
        initConnect();
        //初始化线程去解决失败数据
        //失败队列写入文件..
        //TODO 先注释掉一下模块,错误日志备份策略先走FileAppender
        registyFailedHandle();
        //程序启动后连接不健康失败情况下,检测kafka终端是否健康,并重新创建连接
        registryConnectionChecker();

        fileName = fileProperties.getFileName();
        fileDir = this.kafka.getKafkaBackDir();
        File file = new File(fileDir);
        if (!file.exists()) if (!file.mkdirs())
            throw new RuntimeException("Can not create dir ['" + fileDir + "'] ,maybe your current user no permission or has being used");
        if (Objects.nonNull(fileProperties.getRolling())) {
            rollingPattern = fileProperties.getRolling().getPattern();
        }
        try {
            plantOutputStream(0);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        //清理之前的文件
        revertLogToMQ();
        if (StringUtil.nonEmpty(fileProperties.getFileSize())) {
            //解析file size
            final String sizeReg = "(\\d+)\\s?(MB|KB|GB)";
            Pattern pattern = Pattern.compile(sizeReg);
            Matcher matcher = pattern.matcher(fileProperties.getFileSize());
            if (matcher.find()) {
                int cap = Integer.valueOf(matcher.group(1));
                String unit = matcher.group(2);
                FileCap fileUnit = FileCap.valueOf(unit);
                maxFileSize = cap * fileUnit.size;
            }
        }
    }

    /**
     * Registry kafka connection checker
     * if kafka connection break then reconnect
     */
    private void registryConnectionChecker() {
        Thread reconnect = new Thread(() -> {
            for (; !Thread.currentThread().isInterrupted(); ) {
                try {
                    boolean before = available.get();
                    //检查连接是否通畅
                    ProducerRecord<String, String> producerRecord = new ProducerRecord<>(String.valueOf(config.get("checker_topic")), 0, "", "ping");
                    producer.send(producerRecord, (metadata, exception) -> {
                        if (Objects.isNull(exception)) {
                            available.compareAndSet(false, true);
                            if (!before && available.get()) {
                                //如果之前是链接失败的,现在成功链接后,进行消息回写
                                revertLogToMQ();
                            }
                        } else {
                            available.compareAndSet(true, false);
                        }
                    });
                    TimeUnit.SECONDS.sleep(5);
                } catch (Exception e) {
                    if (e instanceof InterruptedException)
                        Thread.currentThread().interrupt();
                    e.printStackTrace();
                }
            }
        });
        reconnect.setDaemon(true);
        reconnect.setName("log-kafka-appender-reconnect");
        reconnect.start();
    }

    public static void main(String[] args) {
        File file = new File("D:\\test\\kafka\\log.log-2017-10-18-10");
        file.delete();
    }

    private void revertLogToMQ() {
        File file = new File(fileDir);
        if (file.exists()) {
            File[] files = file.listFiles(f -> !f.isDirectory());
            //如果当前文件名跟备份文件一致，不做操作

            for (File logFile : files) {
                if (!fileName.equals(logFile.getName())) {
                    BufferedReader bufferedReader = null;
                    try {
                        bufferedReader = new BufferedReader(new FileReader(logFile));
                        bufferedReader.lines().forEach(txt -> {
                            Log log = FileAppender.UnParseLog(txt);
                            send(log);
                        });
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } finally {
                        if (Objects.nonNull(bufferedReader))
                            try {
                                bufferedReader.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        logFile.delete();
                    }
                }

            }
        }
    }

    /**
     * Close kafka produce connection and set produce as null
     */

    private void disConnect() {
        available.compareAndSet(true, false);
        try {
            if (Objects.nonNull(producer))
                producer.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            producer = null;
        }
    }

    private BufferedWriter backupOutputWrite;

    //    private OutputStream outputStream;

    /**
     * 注册失败日志回写文件
     */
    private void registyFailedHandle() {
        Thread handleFaildLog = new Thread(() -> {
            for (; !Thread.currentThread().isInterrupted(); ) {
                try {
                    //检查kafka客户端是否断开连接,如果断开把日志写入到文件
                    if (!available.get()) {
                        if (!failedQueue.isEmpty()) {
                            Log log = failedQueue.poll(5, TimeUnit.SECONDS);
                            if (Objects.nonNull(log)) {
                                try {
                                    String content = FileAppender.parseLog(log, logProperties);
                                    plantOutputStream(content.length());
                                    backupOutputWrite.write(content);
                                    backupOutputWrite.flush();
                                } catch (Exception e) {
                                    failedQueue.add(log);
                                    e.printStackTrace();
                                } finally {
                                    if (Objects.nonNull(backupOutputWrite))
                                        try {
                                            backupOutputWrite.close();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                }
                            }
                        } else {
                            if (Objects.nonNull(backupOutputWrite))
                                try {
                                    backupOutputWrite.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                } finally {
                                    backupOutputWrite = null;
                                }
                        }
                    } else {
                        //如果链接正常,检查是否有

                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                }
            }
        });
        handleFaildLog.setName("log-kafka-appender-failed-log-handle");
        handleFaildLog.start();
    }

//
//    protected String parseLog(Log log) {
//        StringBuilder stringBuilder = new StringBuilder();
//        stringBuilder.append(Objects.nonNull(log.getApplication()) ? log.getApplication() : "" + Constans.PLACEHOLDER);
//        stringBuilder.append(Objects.nonNull(log.getPid()) ? log.getPid() : "" + Constans.PLACEHOLDER);
//        stringBuilder.append(Objects.nonNull(log.getContentType()) ? log.getContentType() : "" + Constans.PLACEHOLDER);
//        stringBuilder.append(Objects.nonNull(log.getClassName()) ? log.getClassName() : "" + Constans.PLACEHOLDER);
//        stringBuilder.append(Objects.nonNull(log.getMethodName()) ? log.getMethodName() : "" + Constans.PLACEHOLDER);
//        stringBuilder.append(Objects.nonNull(log.getLineNumber()) ? log.getLineNumber() : "" + Constans.PLACEHOLDER);
//        stringBuilder.append(Objects.nonNull(log.getTime()) ? log.getTime() : "" + Constans.PLACEHOLDER);
//        Business business = log.getBusiness();
//        stringBuilder.append(Objects.nonNull(business.getLevel()) ? business.getLevel() : "" + Constans.PLACEHOLDER);
//        stringBuilder.append(Objects.nonNull(business.getModel()) ? business.getModel() : "" + Constans.PLACEHOLDER);
//        stringBuilder.append(Objects.nonNull(business.getVia()) ? business.getVia() : "" + Constans.PLACEHOLDER);
//        stringBuilder.append(Objects.nonNull(business.getContent()) ? business.getContent() : "");
//        return stringBuilder.toString() + Constans.NEWLINE;
//    }

    /*
    private void writeErrorInfo(String dir, String fname, int line) {
        BufferedWriter bufferedWriter = null;
        FileWriter fileWriter = null;
        try {
            fileWriter = new FileWriter(new File(dir + File.separator + Constans.PROPERTY));
            bufferedWriter = new BufferedWriter(fileWriter);
            bufferedWriter.write(Constans.FNAME + "=" + fname);
            bufferedWriter.write("\r\n");
            bufferedWriter.write(Constans.LINE + "=" + line);
            bufferedWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (Objects.nonNull(fileWriter))
                try {
                    fileWriter.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            if (Objects.nonNull(bufferedWriter))
                try {
                    bufferedWriter.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
    }
    */
    String rollingPattern;
    //日志文件名
    String fileName;
    //日志存放路径
    String fileDir;
    int rolling;
    int fileSize;
    int maxFileSize;
    int segmentCount;

    private synchronized boolean plantOutputStream(int size) throws FileNotFoundException {
        boolean change = false;
        String pattern = DateUtil.timeToString(fileProperties.getRolling().getPattern(), System.currentTimeMillis());
        if (StringUtil.nonEmpty(fileProperties.getRolling().getPattern())) {
            //去除'-'占位符,计算文件有效期
            int nowRolling = Integer.valueOf(pattern.replace("-", ""));
            if (nowRolling > rolling) {
                rolling = nowRolling;
                change = true;
            }
        }
        if (Objects.nonNull(backupOutputWrite))
            //文件长度将超过或达到设置上限.分割日志文件
            if (maxFileSize > 0 && (fileSize + size > maxFileSize)) {
                segmentCount++;
                change = true;
            }
        if (change) {
            try {
                if (Objects.nonNull(backupOutputWrite)) {
                    backupOutputWrite.flush();
                    backupOutputWrite.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            fileName = File.separator + fileName + (StringUtil.nonEmpty(String.valueOf(pattern)) ? "-" + pattern : "") + (segmentCount > 0 ? "-" + segmentCount : "");
            backupOutputWrite = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(fileDir + fileName), true)));
        }
        return change;
    }
}