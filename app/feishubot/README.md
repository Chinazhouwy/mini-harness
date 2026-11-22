# feishubot

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
mvn -f miniharness-core/pom.xml -DskipTests install
mvn -f app/feishubot/pom.xml clean test
mvn -f app/feishubot/pom.xml clean package -DskipTests
java -jar app/feishubot/target/feishubot-0.1.0.jar
```

启动服务后访问 `http://localhost:8080/monitor.html` 看深色终端驾驶舱：顶部显示运行状态、已用时间、事件/工具/错误数量；左侧是主 Bot、模型、工具、联网工具与子 Agent 卡片；中央 LIVE FLOW 用发光执行球表示当前环节，进入下一阶段时长出虚线箭头，球沿箭头移动；`DELTA`/`PROGRESS` 只让当前球脉冲，不新增节点，子 Agent 以紫色分叉球显示并在完成后汇回主链路；右侧 EVENT LOG 逐行追加事件，底部 TRACE HISTORY 自动汇总最近 trace，点击可回放历史。无需输入任何 ID，默认跟随最新消息，点「暂停跟随」可固定查看某条 trace。实时流是 `GET /api/stream`（SSE），查询接口是 `GET /api/events?traceId=...`、`GET /api/events?sessionId=...` 或 `GET /api/events?limit=...`（最近事件）。事件按天写入 `logs/feishu-events-YYYY-MM-DD.jsonl`，每条飞书消息共享一个 `traceId`；每条事件带 `replyId`、`toolCallId`、`parentSessionId` 与子 Agent 会话字段，既能按 `traceId` 看一次调用的实时过程，也能按 `sessionId` 回看同一对话的历史。AgentScope 的 Agent、模型、工具、子 Agent 事件通过 `streamEvents()` 接入，监听器也给同一个 `traceId` 写收到消息和发送成功事件。事件总线只在单个 JVM 内，服务重启后历史走 JSONL 查询。

点击左侧角色卡片、中央 LIVE FLOW 节点或右侧日志行，会打开同一个详情浮窗。模型调用显示模型 ID、`replyId`、耗时、入参和出参；工具调用显示工具名、`toolCallId`、耗时、入参和出参；旧事件缺少详情字段时显示「未记录」。`AgentEventStore` 通过 AgentScope 的 `ON_MODEL_CALL` / `ON_ACTING` 中间件把流式 `DELTA` / `PROGRESS` 聚合成 `MODEL_CALL_DETAIL` 和 `TOOL_CALL_DETAIL`，避免把数千条增量事件逐条写入日志；入参与出参最多保留 4000 字符并转成单行，不记录 `GenerateOptions.apiKey`。子 Agent 也挂同一套事件中间件，因此委派链路里的模型/工具详情可以按 `traceId` 一起回看。

日志和中间文件统一锚定在项目根 `/Users/chinazhouwy/doc/MiniHarness`，与从哪里启动无关：日志写到 `logs/feishu-bot.log`，Agent 文件写到 `.feishu-agent-workspace/`。默认从 class/jar 位置向上找 `.git` 定位项目根；也可显式传 `-Dminiharness.home=/path/to/MiniHarness` 覆盖。日志只显示脱敏后的 App ID，不打印 App Secret 或模型密钥。看到“飞书长连接已启动”后，在飞书里给机器人发一条文本即可收到 AI 回复。

也可以在 IntelliJ 里运行 `com.chinazhouwy.feishubot.FeishuBotApplication.main`。

## Java 包结构

| 包 | 职责 |
| --- | --- |
| `com.chinazhouwy.feishubot` | Spring Boot 入口和项目路径定位 |
| `bot` | Bot 注册、Agent 创建、提示词和回复执行 |
| `messaging` | 飞书凭证、长连接、消息解析和发送 |
| `schedule` | 定时任务定义、调度和创建工具 |
| `tool` | 文件、命令、联网和临时子 Agent 工具 |
| `monitor` | 执行事件、模型请求日志和监控接口 |

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

## Agent 文件

小飞从 `src/main/resources/bots/main/` 读取 `AGENTS.md`（规则）、`SOUL.md`（角色）、`USER.md`（偏好）和 `MEMORY.md`（长期记忆）。

初始规则和角色来自原有提示词，偏好和记忆先留空，可手动编辑。文件随应用打包，有内容的文件会在创建小飞时读进提示词。

`spawn_agent` 为每次委派创建临时助手，用内置提示词执行任务并返回结果。子助手的会话由 AgentScope 保存，传回原来的 `session_id` 可以追问。

## 已知边界

- 只处理 `text` 消息；图片、富文本等事件会忽略并在日志里说明。
- AI 会话按飞书 `chat_id` 存在 AgentScope 的 `JsonFileAgentStateStore` 中，默认目录 `logs/feishu-agent-state`，重启后能接续。
- 每次 `write` 会追加一条记录到独立文件 `logs/feishu-write-record.log`，包含时间、`sessionId`、相对路径、绝对 `fileUrl` 和写入结果，方便核对 Agent 生成的中间文件。
- Agent 注册了 Pi 风格四个工具：`read`、`write`、`edit`、`bash`，另在 `FeishuTools` 内声明 `spawnAgent(...)`，底层交给 AgentScope 官方 `SubAgentTool`。文件操作复用 AgentScope Harness 的 `FilesystemTool`，限制在项目根下的 `.feishu-agent-workspace/`；命令执行复用 AgentScope Core 的 `ShellCommandTool`，bash 工作目录也固定为项目根，放开命令白名单，只拦命令串联、越界相对路径等违规写法。`spawn_agent` 用官方 `SubAgentProvider` 每次新建子 Agent、`SubAgentConfig` 管理工具名与持久化会话；子 Agent 暴露 Pi 四工具 + DeepSeek 联网工具（`web_search`、`web_fetch`），并复用同一个 `JsonFileAgentStateStore`；为避免递归委派，子 Agent 不再挂 `spawn_agent`。官方协议参数是必填 `message` 和可选 `session_id`：新建任务只传 `message`，返回文本里的 `session_id` 可用于追问同一子 Agent。
- 另注册 DeepSeek 联网工具 `web_search`、`web_fetch`，直接调用 DeepSeek 官方 Search/Fetch 接口；鉴权复用 `config/openrouter.local.properties` 的 `miniharness.api.key`，不另存密钥。Search 按成功请求计费，Fetch 按成功抓取的网页计费；失败、空结果不计费。
- 飞书要求事件处理尽快返回，所以消息处理提交到共享虚拟线程执行器 `feishu-ai-worker-N`。
- 关闭 Spring 容器时会停止长连接并关闭线程池。
- 如果收不到事件，先检查后台是否选择长连接、事件是否已订阅、应用版本是否已发布。
