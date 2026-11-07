package com.easymall.task;

import java.util.List;

/**
 * 【仅用于测试验证，不参与正式业务运行】模拟突然崩溃的子JVM入口。
 * 由BusinessTaskMySqlIT启动，领取事务提交后直接硬退出，不运行停机钩子。
 * 父测试据此检查：任务仍在数据库中，另一Worker能在租约到期后恢复。
 * 不作为日常Worker启动入口使用。
 */
public final class TaskCrashProbe {
    public static void main(String[] args) throws Exception {
        TaskMySqlFixture fixture = new TaskMySqlFixture();
        BusinessTaskHandler handler = new BusinessTaskHandler() {
            @Override public String taskType() { return args[0]; }
            @Override public void handle(TaskExecution task) {
                task.requireActiveLease();
                // 测试故意跳过优雅停机；23是父测试约定的崩溃退出码，不是业务错误码。
                Runtime.getRuntime().halt(23);
            }
        };
        new BusinessTaskWorker(fixture.service, fixture.properties, List.of(handler)).start();
    }
    private TaskCrashProbe() { }
}
