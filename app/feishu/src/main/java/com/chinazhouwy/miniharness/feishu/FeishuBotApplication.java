package com.chinazhouwy.miniharness.feishu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 入口：Bean 装配完成后 FeishuBotListener 自动建立长连接。 */
@SpringBootApplication(scanBasePackages = "com.chinazhouwy.miniharness")
public class FeishuBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(FeishuBotApplication.class, args);
    }
}
