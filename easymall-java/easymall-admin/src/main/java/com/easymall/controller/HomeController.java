package com.easymall.controller;

import com.easymall.commonent.EsSearchComponent;
import com.easymall.commonent.RedisComponent;
import com.easymall.entity.enums.DateTimePatternEnum;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.query.UserInfoQuery;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.entity.vo.TodayDataVO;
import com.easymall.service.*;
import com.easymall.utils.DateUtil;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/home")
public class HomeController extends ABaseController{
    @Resource
    private StatisticsInfoService statisticsInfoService;
    @Resource
    private ProductInfoService productInfoService;
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private EsSearchComponent esSearchComponent;
    @Resource
    private RagQuestionService ragQuestionService;
    @Resource
    private OrderInfoService orderInfoService;
    @Resource
    private UserInfoService userInfoService;

    /**
     * 获取今日数据(并且获取相比于昨天的增长量)
     * @return
     */
    @RequestMapping("/getTodayData")
    public ResponseVO getTodayData(){
        List<TodayDataVO> result = new ArrayList<>();
        //获取日期

        String today = DateUtil.format(new Date(), DateTimePatternEnum.YYYY_MM_DD.getPattern());
        String yesterday = DateUtil.getBeforeDay(1, DateTimePatternEnum.YYYY_MM_DD.getPattern());

        //订单金额
        //今日营业额
        BigDecimal todayOrderAmount = orderInfoService.getOrderTotalAmount(today, new Integer[]{
                OrderStatusEnum.SHIPPED.getStatus(),
                OrderStatusEnum.COMPLETED.getStatus(),
                OrderStatusEnum.PAID.getStatus()}
        );
        //昨日营业额
        BigDecimal yesterdayOrderAmount = orderInfoService.getOrderTotalAmount(yesterday, new Integer[]{
                OrderStatusEnum.SHIPPED.getStatus(),
                OrderStatusEnum.COMPLETED.getStatus(),
                OrderStatusEnum.PAID.getStatus()}
        );
        TodayDataVO orderAmountData = new TodayDataVO("orderAmount", todayOrderAmount, yesterdayOrderAmount);
        result.add(orderAmountData);

        //订单数量
        OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
        orderInfoQuery.setOrderTimeStart(today);
        orderInfoQuery.setOrderTimeEnd(today);
        Integer todayOrderCount = this.orderInfoService.findCountByParam(orderInfoQuery);
        orderInfoQuery.setOrderTimeStart(yesterday);
        orderInfoQuery.setOrderTimeEnd(yesterday);
        Integer yesterdayOrderCount = this.orderInfoService.findCountByParam(orderInfoQuery);
        TodayDataVO orderCountData = new TodayDataVO("orderCount", new BigDecimal(todayOrderCount), new BigDecimal(yesterdayOrderCount));
        result.add(orderCountData);

        //用户数量
        UserInfoQuery userInfoQuery = new UserInfoQuery();
        userInfoQuery.setJoinTimeStart(today);
        userInfoQuery.setJoinTimeEnd(today);
        Integer todayUserCount = this.userInfoService.findCountByParam(userInfoQuery);

        userInfoQuery.setJoinTimeStart(yesterday);
        userInfoQuery.setJoinTimeEnd(yesterday);
        Integer yesterdayUserCount = this.userInfoService.findCountByParam(userInfoQuery);
        TodayDataVO userCountData = new TodayDataVO("userCount", new BigDecimal(todayUserCount), new BigDecimal(yesterdayUserCount));
        result.add(userCountData);

        //退款数量
        BigDecimal todayRefundAmount = orderInfoService.getOrderTotalAmount(today, new Integer[]{OrderStatusEnum.REFUNDED.getStatus()});
        BigDecimal yesterdayRefundAmount = orderInfoService.getOrderTotalAmount(yesterday, new Integer[]{OrderStatusEnum.REFUNDED.getStatus()});
        TodayDataVO refundAmountData = new TodayDataVO("refundAmount", todayRefundAmount, yesterdayRefundAmount);
        result.add(refundAmountData);

        return getSuccessResponseVO(result);
    }

    /**
     * 获取本周统计数据
     * @return
     */
    @RequestMapping("/loadWeeklyStatisticsData")
    public ResponseVO loadWeeklyStatisticsData(){
        return getSuccessResponseVO(statisticsInfoService.loadWeeklyStatisticsData());
    }

}
