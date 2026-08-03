package com.chinazhouwy.miniharness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MiniHarnessApplication {

    public static void main(String[] args) {
        // 保留 Spring Boot 入口，未来可接入模型和金融数据服务。
        // 当前不需要启动 Spring 容器即可运行 LongTaskHarnessConsole。
        SpringApplication.run(MiniHarnessApplication.class, args);
    }
}
