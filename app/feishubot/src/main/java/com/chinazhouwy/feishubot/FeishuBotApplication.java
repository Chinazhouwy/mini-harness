package com.chinazhouwy.feishubot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 入口：Bean 装配完成后 FeishuBotListener 自动建立长连接。 */
@SpringBootApplication(scanBasePackages = "com.chinazhouwy.miniharness")
public class FeishuBotApplication {
    public static void main(String[] args) {
        // 日志路径也锚定到项目根，避免从任意目录启动时日志散落。
        System.setProperty("logging.file.name",
                ProjectPaths.home().resolve("logs").resolve("feishu-bot.log").toString());
        SpringApplication.run(FeishuBotApplication.class, args);
    }
}
