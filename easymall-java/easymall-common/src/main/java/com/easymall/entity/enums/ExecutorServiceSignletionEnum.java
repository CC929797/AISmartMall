package com.easymall.entity.enums;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public enum ExecutorServiceSignletionEnum {
    INSTANCE;

    private final ExecutorService executorService;

    ExecutorServiceSignletionEnum() {
        executorService = Executors.newFixedThreadPool(50);
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }
}
