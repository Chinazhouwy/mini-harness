# app/feishu：Spring Boot + AgentScope4j 飞书 AI 机器人

这个模块是一个 Spring Boot 常驻服务：

1. 从 `ModelConfig` 指向的本地模型配置创建 AgentScope `OpenAIChatModel` 和 Agent 工厂 Bean；
2. 从本机飞书凭证创建官方 SDK 客户端与长连接监听器 Bean；
3. 收到 `im.message.receive_v1` 的纯文本消息后，调用 AgentScope4j 生成回答，发回原会话。

模型配置沿用 `config/openrouter.local.properties`，飞书凭证沿用 `config/feishu.local.properties`。两个本地文件都已加入 `.gitignore`，不要提交。

## 飞书后台设置

1. 开通机器人能力，创建版本并发布应用。
2. 添加 `im:message` 或 `im:message:send_as_bot` 权限。
3. `事件与回调 > 事件配置`：订阅方式选“使用长连接接收事件”，添加事件 `im.message.receive_v1`。
4. 单聊或群里给机器人发文本消息；群里需要先把机器人加入群。

## 启动

从仓库根目录运行：

```bash
mvn -pl app/feishu -am test
mvn -pl app/feishu -am package -DskipTests
java -jar app/feishu/target/app-feishu-0.1.0.jar
```

Spring Boot 日志同时输出到控制台和 `logs/feishu-bot.log`。日志只显示脱敏后的 App ID，不打印 App Secret 或模型密钥。看到“飞书长连接已启动”后，在飞书里给机器人发一条文本即可收到 AI 回复。

也可以在 IntelliJ 里直接运行 `FeishuBotApplication.main`；不需要再手动运行旧的 `FeishuBotMain`。

## 日志里能看到什么

每收到一条消息会依次打印：

- 事件到达、虚拟线程名；
- `message_id`、`root_id`、`parent_id`、`chat_id`、`chat_type`、`thread_id`、消息类型、创建/更新时间；
- 原始 `content`、`mentions` 数量、`user_agent`；
- 发送者类型、租户 key、open_id / user_id / union_id；
- 解析后的文本长度和正文；
- AgentScope 调用耗时、回复长度与内容；
- 发送结果；失败时打印异常堆栈。

换行、制表等控制字符会转成可见转义。日志不包含 App Secret 或模型密钥。

## 已知边界

- 只处理 `text` 消息；图片、富文本等事件会忽略并在日志里说明。
- AI 回复目前不携带历史上下文，也不使用工具；这是最小可运行版本。
- 飞书要求事件处理尽快返回，所以消息处理提交到共享虚拟线程执行器 `feishu-ai-worker-N`。
- 关闭 Spring 容器时会停止长连接并关闭线程池。
- 如果收不到事件，先检查后台是否选择长连接、事件是否已订阅、应用版本是否已发布。
