package com.quant.strategy.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class ThreadPoolConfig {

    @Bean(name = "backtestExecutor")
    public Executor backtestExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
