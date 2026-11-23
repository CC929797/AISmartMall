package com.easymall.task;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 模拟物流与自动收货的正式业务投递入口。调用方在发货或写物流轨迹的事务里登记任务。
 * 到期时间由 11A 任务表保存，Web 里的 Worker 到期后才调用订单服务。
 */
@Component
public class FulfillmentTasks {
    public static final String LOGISTICS_STEP = "ORDER_LOGISTICS_STEP";
    public static final String AUTO_CONFIRM = "ORDER_AUTO_CONFIRM";

    private final BusinessTaskService tasks;

    public FulfillmentTasks(BusinessTaskService tasks) {
        this.tasks = tasks;
    }

    /**
     * 登记下一条物流轨迹。同一订单和轨迹数重复提交时，由任务表唯一键去重。
     *
     * @param orderId 商城主订单号
     * @param completedRecords 已经写入的轨迹数，发货时为 1
     * @param delay 从当前时刻起等多久才允许领取任务
     */
    public void logisticsStep(String orderId, long completedRecords, Duration delay) {
        tasks.enqueue(new TaskSubmission(LOGISTICS_STEP, orderId, completedRecords, "{}", delay));
    }

    /** 每个订单只登记一次自动收货，所以业务版本固定为 0。 */
    public void autoConfirm(String orderId, Duration delay) {
        tasks.enqueue(new TaskSubmission(AUTO_CONFIRM, orderId, 0, "{}", delay));
    }
}
