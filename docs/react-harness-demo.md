# ReAct Harness：从一个工具循环到已有任务运行时

对应测试：`src/test/java/com/chinazhouwy/miniharness/harness/demo/ReActHarnessDemoTest.java`。

这是一组可以直接在 IDE 点运行的教学实验。固定模型响应、两个本地 Java 工具，不需要 API Key，不读取 `.env`，不访问网络，也不启动 Spring。
没有引入 AgentScope 或改写现有 Harness；本测试手写 ReAct 内循环，然后用你项目真实的 `HarnessOrchestrator` 验证它如何放进一个任务。

## 先运行这一条

在项目根目录执行：

```bash
mvn -Dtest=ReActHarnessDemoTest#twoToolsThenFinalAnswer test
```

然后运行完整 Demo：

```bash
mvn -Dtest=ReActHarnessDemoTest test
```

IDE 中直接打开这个测试类，先点 `twoToolsThenFinalAnswer` 左边的运行按钮。断点依次放在：

1. `MiniReActHarness.run` 中的 `model.generate(...)`：本轮模型拿到什么历史？
2. `history.add(new Assistant(response))`：工具请求先记录下来。
3. `execute(call)`：真正调用 Java 工具；模型自身没有执行工具。
4. `history.add(new Observation(result))`：按 tool_call ID 写回结果。
5. `twoRoundModel` 的第二个分支：下一轮模型确实读取两个结果。

主线输出应类似：

```text
[REASONING] iteration=0, messages=1
[ACTING] id=t1, tool=loadQuestion, status=SUCCESS, output=题目：...
[ACTING] id=t2, tool=loadRubric, status=SUCCESS, output=评分点：...
[REASONING] iteration=1, messages=4
[FINISH] reason=FINAL_ANSWER
```

完整消息顺序：

```text
User
Assistant(tool_call t1 + t2)
Observation(t1)
Observation(t2)
Assistant(final answer)
```

模型输入和结果列表都有断言，不是只打印一段预先写好的日志。`Model` 是明确的测试替身，并未证明真实模型遵循这条路径。

## 九个场景

| 测试 | 要观察的机制 |
| --- | --- |
| `directAnswerDoesNotExecuteTools` | 无工具且文本可见，一轮结束，工具本体零执行 |
| `twoToolsThenFinalAnswer` | 一个推理批次产生两个工具请求，下一轮同时看到两个结果 |
| `toolFailureBecomesAnObservation` | Java 工具异常变成 ERROR 观察值，模型可据此继续 |
| `deniedToolDoesNotExecute` | 授权在本体调用前，DENIED 也有对应 ID 的结果 |
| `invalidArgumentsAndUnknownToolBecomeErrorResults` | 参数校验错误与未知工具名都有明确错误结果 |
| `iterationLimitDoesNotPretendToBeAFinalAnswer` | 达到预算返回 MAX_ITERATIONS，不把未完成当成功 |
| `blankResponseContinuesUntilBudgetIsExhausted` | 没有工具但只有空白文本，仍不算最终答案 |
| `duplicateCallIdsFailBeforeAnyToolRuns` | 同批重复 ID 在任何工具执行前失败 |
| `reactLoopFitsInsideExistingHarness` | ReAct 放在 answer 任务内部；任务验收后保存，重新加载 Store/Orchestrator，再做 report |

## 和项目现有 Harness 怎样衔接

```text
HarnessOrchestrator
  answer TaskNode
    MiniReActHarness.run
      Reasoning -> Tool requests -> Acting -> Observations -> next Reasoning
    TaskExecutionResult + answer.txt
  DemoTaskVerifier
  Checkpoint
  创建新的 Store / Orchestrator，加载磁盘状态
  report TaskNode -> report.txt
```

集成场景会在 JUnit `@TempDir` 中生成真实文本产物与状态文件；不会写入项目的 `data/harness` 或已有金融研究数据。
断言包括：answer 只有一次 Attempt、工具总计执行两次、报告文件包含答案、Mission 最终 SUCCEEDED。

这里模拟的是任务边界恢复：丢弃 Store/Orchestrator 对象后重新读磁盘，不是杀进程，也不是恢复 ReAct 循环内部的任意一步。ReAct 负责一个任务内的模型/工具循环；现有 Harness 负责任务依赖、验收、事件与检查点。

## 与 AgentScope Java v2.0.4 的边界

- 这里只借鉴读过的主线，不声称复制 AgentScope 的实现。
- 用同步 Java 循环、串行工具和简单 ALLOW/DENY，未实现 Reactor、并发批次、ASK 审批、外部 suspended、Session、中间件和 Plan Mode。
- 参数是 `Map<String, String>`，由 Java 方法手工校验，未实现 JSON Schema。
- Demo 到达上限后直接报告 MAX_ITERATIONS；AgentScope 普通路径还会尝试额外 summary 模型调用。
- Demo 把普通工具异常转为 ERROR 结果，没有自动重试；模型异常、协议重复 ID 和授权检查自身的异常会直接失败。
- 工具 ID 配对和任务检查点都不能保证外部副作用 exactly-once。`DemoTaskVerifier` 也只是现有的最小产物验证器，不证明答案的业务正确性。

## 接下来只手改一个点

1. 把 `loadRubric` 改成第一次失败，观察下一轮历史中的 ERROR；再让固定模型修正请求。
2. 把 `maxIterations` 改为 1，观察两次工具执行完后为何仍没有 final answer。
3. 暂时注释掉一条 Observation 的写回，运行主线测试，看第二轮的断言在哪失败。

面试时先讲清：谁决定动作、谁真正执行、结果如何配对、何时继续、为什么停止。之后再讨论调度线程和进程恢复。
