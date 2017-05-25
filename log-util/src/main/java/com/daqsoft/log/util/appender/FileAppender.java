package com.daqsoft.log.util.appender;

import com.daqsoft.commons.core.DateUtil;
import com.daqsoft.commons.core.StringUtil;
import com.daqsoft.log.core.serialize.Log;
import com.daqsoft.log.util.config.*;

import java.io.*;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by ShawnShoper on 2017/4/20.
 * 输出日志到File
 */
public class FileAppender extends Appender {
    FileProperties fileProperties;
    private OutputStream outputStream;
    String rollingPattern;
    String fileName;
    String fileDir;
    int rolling;
    int fileSize;
    int maxFileSize;
    int segmentCount;
    List<LogPattern> logPatterns;
    public final static String PERCENT = "%";

    public FileAppender(final LogProperties logProperties, final List<LogPattern> logPatterns) {
        super(logProperties);
        this.fileProperties = logProperties.getFileProperties();
        this.logPatterns = logPatterns;
    }

    @Override
    public void init() {
        fileName = fileProperties.getFileName();
        fileDir = this.fileProperties.getFileDir();
        File file = new File(fileDir);
        if (!file.exists()) if(!file.mkdirs())throw new RuntimeException("Can not create dir ['" + fileDir + "'] ,maybe your current user no permission or has being used");
        if (Objects.nonNull(fileProperties.getRolling())) {
            rollingPattern = fileProperties.getRolling().getPattern();
        }
        if (StringUtil.nonEmpty(fileProperties.getFileSize())) {
            //解析file size
            final String sizeReg = "(\\d+)\\s?(MB|KB|GB)";
            Pattern pattern = Pattern.compile(sizeReg);
            Matcher matcher = pattern.matcher(fileProperties.getFileSize());
            if (matcher.find()) {
                int cap = Integer.valueOf(matcher.group(1));
                String unit = matcher.group(2);
                FileUnit fileUnit = FileUnit.valueOf(unit);
                maxFileSize = cap * fileUnit.size;
            }
        }
//        String fileName = fileProperties.getFileDir() + File.separator + fileProperties.getFileName();
    }


    class FileWriter {

    }

    /**
     * 检查输出流是否需要重定向
     * @return 是否重定向
     */
    private synchronized boolean plantOutputStream(int size) throws FileNotFoundException {
        boolean change = false;
        String pattern = DateUtil.timeToString(fileProperties.getRolling().getPattern(), System.currentTimeMillis());
        if (StringUtil.nonEmpty(rollingPattern)) {
            //去除'-'占位符,计算文件有效期
            int nowRolling = Integer.valueOf(pattern.replace("-",""));
            if (nowRolling > rolling) {
                rolling = nowRolling;
                change = true;
            }
        }
        if (Objects.nonNull(outputStream))
            //文件长度将超过或达到设置上限.分割日志文件
            if (maxFileSize > 0 && (fileSize + size > maxFileSize)) {
                segmentCount++;
                change = true;
            }
        if (change) {
            try {
                if (Objects.nonNull(outputStream)) {
                    outputStream.flush();
                    outputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            outputStream = new FileOutputStream(new File(fileDir + File.separator + fileName + (StringUtil.nonEmpty(String.valueOf(pattern)) ? "-" + pattern : "") + (segmentCount > 0 ? "-" + segmentCount : "")), true);
        }
        return change;
    }

    @Override
    public void write(Log log) throws IOException {
        byte[] data = parseLog(log).getBytes();
        plantOutputStream(data.length);
        outputStream.write(data);
        outputStream.flush();
    }

    @Override
    public void destroy() {
        if (Objects.nonNull(outputStream))
            try {
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
    }

    protected String parseLog(Log log) {
        StringBuilder stringBuilder = new StringBuilder();
        logPatterns.stream().map(e -> {
            String name = e.getName();
            String tmp = null;
            if (Tag.T.name.equals(name)) {
                String time = DateUtil.dateToString(e.getPattern(), new Date(log.getTime()));
                tmp = time;
            } else if (Tag.C.name.equals(name)) {
                tmp = log.getBusiness().getContent();
            } else if (Tag.L.name.equals(name)) {
                String tag_name = log.getBusiness().getLevel();
                tmp = tag_name;
            } else if (Tag.P.name.equals(name)) {
                tmp = String.valueOf(log.getPid());
            } else if (Tag.MN.name.equals(name)) {
                tmp = String.valueOf(log.getMethodName());
            } else if (Tag.LN.name.equals(name)) {
                tmp = String.valueOf(log.getLineNumber());
            } else if (Tag.CN.name.equals(name)) {
                tmp = String.valueOf(log.getClassName());
            }
            return tmp + "\001";
        }).filter(Objects::nonNull).forEach(stringBuilder::append);
        stringBuilder.setLength(stringBuilder.length() - 1);
        return stringBuilder.toString() + "\r\n";
    }
}
