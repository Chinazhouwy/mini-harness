package com.chinazhouwy.miniharness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MiniHarnessApplication {

    public static void main(String[] args) {
        // 这个入口用于验证 Spring Boot 与 application.yml 配置能否正常加载。
        // 当前真正可交互的学习原型是 MiniHarnessDemo.main()，它不经过 Spring 容器。
        SpringApplication.run(MiniHarnessApplication.class, args);
    }
}
