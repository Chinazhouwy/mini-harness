# 实验 001：用 Spring AI 原生日志看见模型调用

## 为什么补这个 Trace

控制台原型现在一次用户输入往往不是一次模型调用。例如输入“下一题”时，程序会先识别意图，
再生成题目；输入一段回答时，会先识别意图，再做结构化评测，最后生成自然语言反馈。

如果只看最后屏幕上的 `Assistant:` 文本，很难判断问题发生在：

- 意图识别错了；
- 历史消息没有带上；
- System Prompt 写得不清楚；
- 当前调用额外加的指令和历史相互冲突；
- 还是模型本身返回得不稳定。

Spring AI 已经提供了 `SimpleLoggerAdvisor`：它会在调用前记录 `ChatClientRequest`，调用后记录
`ChatResponse`。因此当前 Demo 直接使用这个原生 Advisor，而不是再手写一套 Prompt 日志器。

## Trace 的阅读顺序

`SimpleLoggerAdvisor` 打印的是已经组装完成的 `ChatClientRequest`。其中的 Prompt 按 Spring AI
最终顺序包含：

```text
System Prompt
→ messages(history)
→ 本次 user(...)
→ 模型返回
```

例如用户第一次输入“下一题”时：

1. **意图识别**没有历史消息，当前 `user(...)` 是“下一题”。
2. Java 把“下一题”写入 `history`。
3. **生成下一题**能看到刚写入的用户消息，然后再收到“请换一道不同知识点……”这个额外指令。
4. 模型返回的问题由 Java 写入 `history`，为下一轮准备上下文。

这也解释了一个容易误会的写法：

```java
.messages(history)
.user(instruction)
```

这里的 `history` 不是替代当前指令。前者是已有对话，后者是这次调用最后附加给模型的要求。
Spring AI 会固定把 System 放在最前、历史放在中间、当前 `user(...)` 放在最后。

## 结构化输出要额外注意什么

评测调用使用了：

```java
.entity(AnswerEvaluation.class)
```

它让 Spring AI 将模型文字转换成 Java `record`。Trace 会显示业务代码自己的评测 Prompt，
并标出目标类型；但 Spring AI 为了让模型返回可转换的数据，还可能在底层加入 JSON 输出格式约束。

因此，当前 Trace 的定位是：

> 帮我看清 MiniHarness 自己写了什么、history 是否正确，而不是抓取 HTTP 请求的全部原始字节。

## 现在怎样使用

直接运行 `MiniHarnessDemo`，尝试依次输入：

```text
下一题
我先说一下，我觉得线程池就是创建几个线程执行任务
给一个提示
```

每次观察四件事：

1. 本轮用户输入有没有恰好出现一次。
2. 当前题和上一轮助手回复是否真的在 history 里。
3. 额外 `user(...)` 指令是否与当前任务匹配。
4. 模型返回是否符合这次调用的职责。

## `[FLOW]` 和 Spring AI 日志分别看什么

`SimpleLoggerAdvisor` 不知道“这次调用在面试业务里叫意图识别还是出题”，它只观察通用的
`ChatClient` 请求和响应。因此 `MiniHarnessDemo` 只保留两条非常小的 `[FLOW]` 输出：

- 意图识别前，当前用户输入还没有写进 `history`；
- 意图识别后，用户输入被写入 `history`，后续调用可以看见它。

前者解释 Java 流程，后者解释 Spring AI 调用。两者合在一起，正好够当前学习，不需要监控平台。

## 当前限制

- Trace 会包含用户文本，只能用于本地 Demo。
- 它不会输出 API Key。
- 它不记录完整 HTTP Body、请求头或 Provider 原始响应。
- 当前没有 Trace ID、持久化 Trace 或并发关联；这些问题只有在控制台 Demo 的真实观察不够用时再处理。
