package com.quant.strategy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 策略服务启动类
 * 提供基于Ta4j的技术指标回测和信号生成功能
 */
@SpringBootApplication
@EnableFeignClients
public class StrategyServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(StrategyServiceApplication.class, args);
    }
}