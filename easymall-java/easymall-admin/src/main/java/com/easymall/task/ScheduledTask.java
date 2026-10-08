package com.easymall.task;

import com.easymall.entity.enums.DateTimePatternEnum;
import com.easymall.service.StatisticsInfoService;
import com.easymall.utils.DateUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ScheduledTask {
    @Resource
    private StatisticsInfoService statisticsInfoService;

    /**
     * 每日凌晨一点，统计前一天的数据
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void statisticsTask(){
        String yesterday = DateUtil.getBeforeDay(1, DateTimePatternEnum.YYYY_MM_DD.getPattern());
        statisticsInfoService.statisticsData(yesterday);
    }
}
