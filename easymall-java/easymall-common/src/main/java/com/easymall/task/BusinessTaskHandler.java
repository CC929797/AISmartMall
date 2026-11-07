package com.easymall.task;

/**
 * 【后续业务扩展接口】定义“一种任务具体要做什么”，由独立Worker按taskType调用。
 * <p>后续订单超时、商品同步等各自实现此接口，并在Worker入口显式注册。
 * 框架按至少一次执行设计：同一任务可能再次执行，处理器必须自行保证业务幂等；
 * 外部写入还需业务版本或幂等键保护，不能靠租约实现恰好一次。
 */
public interface BusinessTaskHandler {
    /** 声明本处理器支持的类型；必须与投递时的taskType一致，且不能重复注册。 */
    String taskType();
    /** 在领取事务提交后执行；处理器需要数据库事务时，应自行划定业务事务边界。 */
    void handle(TaskExecution task) throws Exception;
}
