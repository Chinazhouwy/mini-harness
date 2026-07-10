# MiniHarness

Spring AI 快速入门项目，演示如何用最少的代码调用大模型。

## 环境要求

- Java 21+
- Maven 3.9+

## 快速开始

### 1. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填入真实的 API Key、Base URL 和模型名称
```

加载环境变量：

```bash
export $(cat .env | xargs)
```

### 2. 启动应用

```bash
mvn spring-boot:run
```

应用启动后会自动发送一条 Prompt 给模型并打印回复。

### 3. 运行测试

```bash
mvn test
```

## 核心代码

只有两个类：

- `MiniHarnessApplication` — Spring Boot 入口
- `ChatDemo` — 注入 `ChatClient.Builder`，构建 `ChatClient`，调用 `.prompt().user(...).call().content()` 拿到模型回复

```java
@Component
public class ChatDemo implements CommandLineRunner {

    private final ChatClient chatClient;

    public ChatDemo(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public void run(String... args) {
        String response = chatClient.prompt()
                .user("用一句话介绍什么是 Spring AI")
                .call()
                .content();
        System.out.println(response);
    }
}
```

三步即可：**注入 Builder → 构建 Client → prompt().user().call().content()**。

## 技术栈

- Java 21
- Spring Boot 4.1.0
- Spring AI 2.0.0
- Maven
