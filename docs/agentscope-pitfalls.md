# AgentScope 踩坑记录（写文章素材）

记录实测中遇到的真实细节，都可用代码复现。**第 2、3 节已按实测更正过一版**（早期结论有误，见文末“被推翻的说法”）。

## 0. 实测数据（先说结论）

环境：`agentscope 2.0.4` + DeepSeek 官方（`https://api.deepseek.com`，`deepseek-flash`）+ `HarnessAgent` 全默认工具集。
探针代码：同一 `Weather` record 的结构化调用，每次打印 `metadata._structured_output` 是否存在。

| 会话策略 | 结果 |
| --- | --- |
| 每次新 `sessionId`（5 次） | **5/5 成功**，`_structured_output=true` |
| 复用同一 `sessionId`（5 次） | **第 1 次成功，第 2–5 次全失败**（`_structured_output=false`，模型直接回文本） |

失败时模型调用次数从 `4 → 2 → 1` 递减 —— 因为第二轮的上下文里**已经有一份完整答案**，模型不再查工具、也不再调 `generate_response`，直接输出文本。

## 1. `observe()` 注入的消息，配置了文件 store 时默认不落盘

- `observe()` 内部就是往 `getAgentState().contextMutable()` 里加一条 `Msg`（`ReActAgent.doObserve`）。
- 但配置了 `stateStore` 后，`call()` **每次都会从 store 重读会话状态**（源码注释：`state is always reloaded from the store at the beginning of each call`），内存里被 `observe` 加上的那条会被文件内容覆盖。
- 绕法：注入后手动 `store.save(null, sessionId, "agent_state", agent.getAgentState())` 落盘。
- 另有一个隐藏前提：`observe()` 走无参 `getAgentState()`（`userId=null` + `defaultSessionId`），`call` 走 `RuntimeContext` 的 `(userId, sessionId)`。槽位 key 是二元组，两边不一致就落在不同会话上，注入当然看不到。测试里让 listener 的 `RuntimeContext` 不带 `userId`，并配 `.defaultSessionId(...)` 才能对上。

## 2. 会话状态按 `(userId, sessionId)` 持久化，复用会话会让“同一问题”第二次开始失败

- 落盘布局（`JsonFileAgentStateStore` 源码）：`<root>/<safe(userId)>/<safe(sessionId)>/agent_state.json`，`userId==null` 时用 `__anon__`。所以换 `userId` 或换 `sessionId` 都等于换一个**空会话槽**。
- 实测：`BuilderTest#test06StructuredOutput` 用固定 `sessionId=sStructured`，IDE 里“重复运行 5 次”：第 1 次过，第 2–5 次挂 `IllegalStateException: No structured output in message metadata`。
- 失败时的会话文件里堆着 14 条消息（前几轮的 `web_search` / `web_fetch` 工具回包 + 答案）。**但失败机制不是“上下文被撑爆”**，而是：历史里已有答案，模型直接文本作答，跳过了 `generate_response` 工具调用（调用次数 4→2→1 可证）。
- 结论：测试每个方法都用**随机 sessionId + 临时目录**（或每次清空状态目录），这是决定性的，不是“偶尔不稳”。

## 3. `generate_response` 兜底路径：`_structured_output` 只在该工具被调用时才有

源码链路（`ReActAgent`）：

1. `doStructuredCall` 判断 `model.supportsNativeStructuredOutput()`；为 true 走 `doNativeStructuredCall`，抛错则 `.onErrorResume` 降级到 `doFallbackStructuredCall`；
2. 原生路径 `wrapNativeStructuredResult`：把返回文本 `fromJson` 解析后写进 `metadata["_structured_output"]`；
3. 降级路径：注入合成工具 `generate_response`（参数外层包一层 `{"response": <你的record schema>}`），只有模型真的调用了它，`extractStructuredResult → extractResponseData` 才会把 `response` 搬到 `metadata["_structured_output"]`；
4. `Msg.getStructuredData(Class)` 在 key 缺失时直接抛 `IllegalStateException`。

所以：**模型不调这个工具 = 没有结构化输出 = 抛异常**。这不是 bug，是兜底路径的固有前提。

## 4. `StructuredOutputReminder.TOOL_CHOICE` 在 2.0.4 里并没有接线

- 源码里有枚举 `io.agentscope.core.model.StructuredOutputReminder`，注释写着 “默认 TOOL_CHOICE：若模型没调 generate_response，下一轮用 tool_choice 强制”；
- 但全量搜 `agentscope-core-2.0.4` 源码，该枚举**只在自身文件和 `MessageMetadataKeys` 的 javadoc 里出现**，`ReActAgent` 主循环没有任何引用；
- 实测与此一致：模型回文本后循环直接结束（`modelCalls=2` 就收工），没有“强制下一轮”。
- 也就是说：**这一版没有任何自动补救机制**，别指望“框架会自己把模型掰回来”。

## 5. DeepSeek 官方不支持 `json_schema` 形态的 `response_format`

- 实测报错：`This response_format type is unavailable now`（400 `invalid_request_error`），每次都触发 `Native structured output failed ... falling back to synthetic tool path`。
- 而且 `DeepSeekModelProvider` 源码里本来就写了 `.nativeStructuredOutput(false)`，框架默认就不走原生；
- 更关键：**手动设 `.nativeStructuredOutput(true)` 并不能让它变稳**，只会多一次注定 400 的请求再降级（实测两者成功率一致：新会话 5/5）。
- 结论：DeepSeek 上就别开 native，直接用降级路径，并用新会话保证可复现。

## 6. `@JsonPropertyDescription` 只影响“填空表注释”，不影响字段名映射

- schema 由 Jackson 的 `@JsonPropertyDescription`（或 `@ToolParam`）生成 `description`；
- 没有描述时，`location`/`temperature`/`condition` 靠字段名本身让模型猜语义，多数猜对；
- 描述与字段名错位（`location` 的描述写成“温度”）时，模型仍按**字段名**填：实测 `location=上海, temperature=19°C, condition=阴`；
- 描述是给模型的补充说明，**不是 Java 侧校验，也不会改变键名映射**。

## 7. 日志噪音与系统消息组装

- `HarnessAgent` 会在系统消息里自动拼入工作区、记忆策略、子 agent、权限等大段上下文，长度远超用户写的 `sysPrompt`——这是“完整提示词”日志难读的主因；
- 想看真正发给模型的完整消息，用 `MiddlewareBase.onModelCall` 拦 `ModelCallInput.messages()`；内置的 `AgentTraceMiddleware` 只打摘要（INFO）或截断内容（DEBUG），不打印全文。

## 8. 生产环境怎么既保留会话、又拿到结构化结果（实测过的三种打法）

空会话只是**测试**里保证可复现的手段，不是产品方案。生产上三种可行做法：

### 8.1 强制 `tool_choice`（确定性最好，但会牺牲第一轮的工具探索）

```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey(...).modelName(...).baseUrl(...)
        .nativeStructuredOutput(false)
        .generateOptions(GenerateOptions.builder()
                .toolChoice(new ToolChoice.Specific("generate_response"))
                // DeepSeek 必加：否则 400 "Thinking mode does not support this tool_choice"
                .additionalBodyParam("thinking", Map.of("type", "disabled"))
                .build())
        .build();
```

实测：**复用同一个 sessionId 连跑 5 次，5/5 都拿到 `_structured_output`**（对照组不加 `tool_choice` 是 1/5）。
适合"纯抽取"场景：数据已经在提示词里，不需要模型先去查工具。

两个坑：
- DeepSeek 默认 thinking 模式**不支持 tool_choice**，必须同时关掉 thinking，否则每次 400；
- 强制从第一轮生效，模型没法先调用业务工具（比如先 `web_search` 再交作业），所以不适合"先查再填"的流程。

### 8.2 两段式：先自由、失败再补一句（保留工具探索）

```java
Msg ret = agent.call(List.of(new UserMessage(q)), Weather.class, ctx).block();
if (!ret.hasStructuredData()) {
    ret = agent.call(List.of(new UserMessage("请调用 generate_response 工具提交最终结果，不要用纯文本回答。")),
                     Weather.class, ctx).block();   // 同一个 ctx，历史连续
}
```

实测：复用会话时第一次 MISS 的那轮，补这一句后变成了 OK。代价是多一次模型调用，胜率不是 100%。

### 8.3 「第一轮自由、第二轮强制」= 官方枚举描述的方案，但 2.0.4 没接线

`StructuredOutputReminder.TOOL_CHOICE` 的注释正是 8.2 的理想形态（自动用 tool_choice 强制），但源码里该枚举未被 `ReActAgent` 引用。要做只能自己在应用层实现（8.2）或用 middleware 改 `ModelCallInput.options()`。

### 8.4 关于 `.nativeStructuredOutput(...)` 的准确理解

- 它是**能力声明**，不是"静音开关"：`true` = 框架会发 `response_format: {"type":"json_schema",...}`；`false` = 直接走合成 `generate_response` 工具；
- DeepSeek 官方只支持 `{"type":"json_object"}`（无 schema），发 `json_schema` 必 400，所以 DeepSeek/GLM/Kimi/MiniMax 的 Provider 源码里都硬编码 `false`；
- 换到 OpenAI gpt-4o 这类真支持 `json_schema` 的模型，就该设 `true`，那才是真正"一次成功"的路径。

## 被推翻的说法（早期误解，别再写进文章）

| 早期说法 | 实际情况 |
| --- | --- |
| “脏状态把上下文撑爆/工具回路导致失败” | 上下文变小、模型调用次数反而降到 1–2；真正原因是历史里已有答案，模型不再调工具 |
| “`.nativeStructuredOutput(true)` 能稳定结构化输出” | DeepSeek 官方拒绝 `json_schema`，永远降级；两种设置成功率一致 |
| “换 userId 就说明框架按用户区别对待” | 只是换了个空的会话槽位（`__anon__` / 新 userId 目录） |
| “重试等待成功就行” | 复用同一会话时失败是确定性的（1 次过、之后全挂），重试没用；换新会话才是解 |
