# 长程任务 Harness v0 骨架

这组代码放在 `com.chinazhouwy.miniharness.harness`，作为独立的任务运行时存在，不依赖具体业务领域。
后续可以在它上面增加股票研究等领域模型，而不需要把领域状态混入 Harness 核心。

## 1. 长程任务和普通 Chat 的区别

普通 Chat 主要关心“这一轮要给模型什么上下文，以及模型返回了什么文本”。长程任务还要回答：

- 任务可以拆成哪些阶段？阶段之间有什么依赖？
- 每个阶段的输入、输出和完成条件是什么？
- 进程退出后，下一次从哪里继续？
- 某一次工具或模型调用失败时，是否可以重试，最多重试几次？
- 模型说“完成了”以后，谁来检查结果确实满足条件？

因此这个骨架把任务拆成 `Mission -> TaskNode -> TaskAttempt -> Artifact`，并用事件和快照保存运行事实。
第一版不调用真实 LLM，先让这些边界在确定性的本地 Demo 中可观察、可测试。

## 2. 核心对象的区别

| 对象 | 作用 | 一个简单例子 |
|---|---|---|
| `Mission` | 一次长期任务及其目标、验收标准和生命周期 | 完成一个可回放的长程任务 Demo |
| `TaskNode` | 任务图中的一个阶段，包含依赖、状态和最大尝试次数 | `execute` 依赖 `prepare` |
| `TaskAttempt` | 某个 TaskNode 的一次具体执行记录 | `execute` 的第 1 次尝试失败 |
| `ArtifactRef` | 可以被后续节点复用的产物引用 | `prepare` 生成的文本摘要 |
| `HarnessEvent` | 不可变的运行事实 | `TASK_ACCEPTED`、`TASK_RETRY_SCHEDULED` |
| `Checkpoint` | 可恢复的 Mission、任务、尝试和产物快照 | 最近一次已保存到 sequence=18 |
| `VerifierResult` | 对一次执行结果的独立判断 | `PASS`、`RETRY` 或 `BLOCK` |

状态不能通过模型文本或控制台输出直接修改。状态转换必须经过 `Mission.transitionTo(...)`、
`TaskNode.transitionTo(...)`，并由 `StateReducer` 在事件 replay 时再次校验。

## 3. 为什么 ChatMemory 不是工作流状态

ChatMemory 只是为后续模型调用补回一部分对话上下文。它可能被截断、摘要或按会话策略清理，
也不天然记录“哪一个任务已经被验证接受”“本次尝试产生了哪个 Artifact”“下一次最多还能重试几次”。

工作流状态需要结构化、可检查、可回放，并且不应该依赖某一段 Prompt 是否仍然存在。因此 Harness
使用 `Checkpoint` 和 append-only 事件日志；未来接入 Spring AI 时，ChatMemory 仍然只是模型上下文的一部分。

## 4. 为什么 Verifier 不能省略

执行器返回结果，只能说明“执行器返回了某个结果”。它不等于任务满足验收条件。Demo 中：

1. `DeterministicTaskExecutor` 产生摘要和 Artifact。
2. `DemoTaskVerifier` 检查执行是否成功，以及是否真的产生了可复用 Artifact。
3. 只有 `VerifierResult.PASS` 才能让 Task 进入 `ACCEPTED`。

失败会进入 `RETRYABLE` 并回到 `READY`，达到 `maxAttempts` 后进入 `BLOCKED`。因此“模型说完成了”
不会直接变成最终成功。

## 5. 为什么 `runUntilPause` 必须有 `maxSteps`

工具失败、错误依赖或未来模型反复提出相同动作，都可能让一个没有上限的循环永远运行。`runUntilPause`
每次只调用一次 `advanceOneStep`，并在以下情况停止：Mission 完成、失败、阻塞、等待输入、没有可调度任务，
或者达到调用方传入的 `maxSteps`。

## 6. 如何模拟进程重启

测试和本地运行都可以这样做：

1. 用 `FileHarnessStateStore` 创建 Mission，并执行一到两个步骤。
2. 丢弃旧的 `HarnessOrchestrator` 和 `FileHarnessStateStore` 对象。
3. 用同一个 `data/harness/` 目录创建新的 Store 和 Orchestrator。
4. 调用 `resume(missionId)`，再调用 `runUntilPause(missionId, maxSteps)`。

每个 Mission 使用：

```text
data/harness/mission-{id}.snapshot.json
data/harness/mission-{id}.events.jsonl
```

加载时先读 snapshot，再应用 `lastEventSequence` 之后的事件。如果事件已经写入、snapshot 尚未更新，
这些事件会被 replay。Task 已经有 `TASK_ACCEPTED` 事件时，恢复后的调度器会看到 `ACCEPTED`，不会再创建
第二个成功 Attempt。snapshot 写入使用同目录临时文件，再尝试原子移动；事件日志按 JSONL 追加，sequence
必须连续递增。

## 7. 运行 Demo

这个入口不需要 API Key，也不访问网络：

```bash
mvn -Dtest=HarnessOrchestratorTest,StateTransitionTest test
mvn test
```

也可以运行控制台入口：

```bash
mvn -DskipTests compile
mvn dependency:build-classpath -Dmdep.outputFile=target/harness-classpath.txt -Dmdep.includeScope=runtime
java -cp "target/classes:$(cat target/harness-classpath.txt)" \
  com.chinazhouwy.miniharness.harness.demo.LongTaskHarnessConsole
```

在 IDE 中直接运行 `LongTaskHarnessConsole.main` 更简单。它会创建 `prepare -> execute -> verify -> report`
任务图，每次推进一个节点，打印事件、当前状态和 Artifact。

## 8. 当前版本的限制

当前版本：

- 单进程
- 本地 JSON/JSONL
- 单节点串行执行
- 无真实 LLM
- 无多 Agent
- 无并行调度
- 无数据库
- 无分布式恢复

此外，Artifact 目前保存的是可回放的引用、描述和摘要，不是完整的内容仓库；文件事件日志也不适合多进程
同时写入。`synchronized` 只保护同一个 JVM 内的调用。

## 9. 未来如何接入 Spring AI

最小改动点是替换一个 `TaskExecutor` 实现：让某个节点内部通过 `ChatClient` 或 `ChatModel` 生成
`TaskExecutionResult`，保留现有 `HarnessOrchestrator`、`HarnessStateStore` 和 `TaskVerifier`。

未来模型只能负责：

```text
提出下一步建议 / 生成节点内部结果
```

Java Harness 仍然负责：

```text
权限
状态
调度
持久化
验证
重试
暂停
恢复
最终完成判断
```

这样接入 Spring AI 不会把 `Checkpoint` 替换成 ChatMemory，也不会让 Prompt 变成隐式状态机。
