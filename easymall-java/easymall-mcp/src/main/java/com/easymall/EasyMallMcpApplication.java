package com.easymall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(scanBasePackages = {"com.easymall"})
@MapperScan("com.easymall.mappers")
@EnableTransactionManagement
@EnableAsync
@EnableScheduling
public class EasyMallMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(EasyMallMcpApplication.class, args);
    }
}
