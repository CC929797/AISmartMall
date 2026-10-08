package com.easymall.utils;


import com.easymall.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Slf4j
public class DateUtil {
    private static final Object lookObj = new Object();
    private static Map<String,ThreadLocal<SimpleDateFormat>> sdfMap = new HashMap<>();

    /**
     *
     * @param dateString 日期时间字符串
     * @param format     格式字符串（如"yyyy-MM-dd HH:mm:ss"）
     * @return 转换后的Date对象，失败返回null
     */
    public static Date parse(String dateString, String format) {
        if (dateString == null || dateString.trim().isEmpty() || format == null || format.trim().isEmpty()) {
            return null;
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            LocalDateTime localDateTime;
            // 根据格式判断是日期、时间还是日期时间
            Boolean hasDate = format.contains("y") || format.contains("M") || format.contains("d");
            Boolean hasTime = format.contains("H") || format.contains("h") || format.contains("m") || format.contains("s");
            if (hasDate && hasTime) {
                localDateTime = LocalDateTime.parse(dateString, formatter);
            } else if (hasDate) {
                LocalDate localDate = LocalDate.parse(dateString, formatter);
                localDateTime = localDate.atStartOfDay(); // 补充时间为00:00:00
            } else {
                LocalTime localTime = LocalTime.parse(dateString, formatter);
                localDateTime = LocalDate.now().atTime(localTime); // 补充当前日期
            }
            return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            log.error("转换日期异常", e);
            return null;
        }
    }

    /**
     * 将Date对象按指定格式转换为字符串
     * 内部先转换为LocalDateTime，再进行格式化
     *
     * @param date   要转换的Date对象
     * @param format 格式字符串（如"yyyy-MM-dd"）
     * @return 格式化后的日期字符串，参数无效返回空字符串
     */
    public static String format(Date date, String format) {
        if (date == null || format == null || format.trim().isEmpty()) {
            return null;
        }
        try {
            LocalDateTime localDateTime = LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            return localDateTime.atZone(ZoneId.systemDefault()).format(formatter);
        } catch (IllegalArgumentException e) {
            log.error("日期格式化异常", e);
            return null;
        }
    }


    /**
     * 获取当前时间后几分钟的时间
     * @param min 几分钟
     * @param pattern 日期格式
     */
    public static String getMinAfter(int min, String pattern) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, min);
        return format(c.getTime(), pattern);
    }

    /**
     * 获取前几天的日期
     * @param day 几天
     * @param pattern 日期格式
     */
    public static String getBeforeDay(Integer day,String pattern) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -day);
        return format(c.getTime(), pattern);
    }

    /**
     * 获取日期范围
     * @param startDateStr 开始日期
     * @param endDateStr 结束日期
     * @param pattern 日期格式
     * @return 日期范围
     */
    public static List<String> getDateRange(String startDateStr, String endDateStr, String pattern) {
        List<String> dateList = new ArrayList<>();
        LocalDate startDate = LocalDate.parse(startDateStr, DateTimeFormatter.ofPattern(pattern));
        LocalDate endDate = LocalDate.parse(endDateStr, DateTimeFormatter.ofPattern(pattern));
        if(startDate.isAfter(endDate)){
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        LocalDate currentDate = startDate;
        while (!currentDate.isAfter(endDate)) {
          dateList.add(currentDate.format(DateTimeFormatter.ofPattern(pattern)));
          currentDate = currentDate.plusDays(1);
        }
        return dateList;
    }
}
