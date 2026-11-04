# AgentScope 主线、Spring AI 对照：渐进式源码与代码学习路线

更新日期：2026-10-29。

这份路线面向有 Java 后端经验、想读懂 Agent 运行时的人。每一关都先提出一个问题，再读一小段真实源码，最后亲手写一个可验证的测试。读完以后，你应该能讲清“一次调用为什么继续、为什么停止、状态如何变化”，而不只是记住 Builder API。

**先完成第 01～03 关。不要第一天就把后面的权限、恢复、Plan Mode 和多 Agent 全部写出来。** 后续每关以测试和口头解释通过为准，不按天数赶进度。

## 一、先把这次学习的对象分清楚

| 学习对象 | 这次怎样使用 | 不能混淆成什么 |
| --- | --- | --- |
| AgentScope Java `ReActAgent` | 前半程的真实运行主体，使用框架自己的循环、工具与状态 | 自己写一个 while 后叫它 AgentScope Demo |
| AgentScope `HarnessAgent` | 后半程再接工作区、Harness Middleware 和 Plan Mode | 在 `ReActAgent` 外面套个名字就算读过 Harness |
| Spring AI 2.0 | 最后用真实 `ChatClient` 和 `ToolCallingAdvisor` 做同题对照 | 重新写一套假的 Spring AI 循环 |
| 你的 MiniHarness | 可选的最终任务适配：Mission、Task、Artifact、Verifier 与检查点 | 把聊天历史当业务任务状态 |

固定模型响应是允许的：你实现框架的 `Model` 接口，替代外部网络服务，但运行主体仍然是真实框架。固定响应不是“假装调了框架”，也不能证明真实模型会遵循同样的行动路径。

项目已有的 `ReActHarnessDemoTest` 是手写机制实验。本路线不把它当 AgentScope 或 Spring AI 的集成验收。

### 版本与证据

- AgentScope Java：`v2.0.4`，固定提交 `0b6743b263ba8e39eb417e35048026f1e0196548`。
- Spring AI：源码对照先按项目当前的 `2.0.0`；2026-10-29 核验的稳定发布为 `2.0.2`，升级后要重新核对行为。
- Java：示例使用 Java 21 语言级别，与 MiniHarness 配置一致。第一关验证时实际使用 JDK 25，编译参数为 `release 21`；不把这写成“已经在 JDK 21 JVM 测过”。
- AgentScope 原仓库只读，学习测试放自己的目录，不给上游源文件塞大量中文注释。

依据：[AgentScope v2.0.4 发布](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.4)、[Spring AI v2.0.2 发布](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.2)。

## 二、路线总览：每关具体要交付什么

下面的类名是建议你创建的学习代码，不是框架已有 API。

| 关卡 | 本关问题 | 要写的代码或记录 | 通过条件 |
| --- | --- | --- | --- |
| 00 | 从哪里开始，不被几千行淹没？ | `00-source-map.md` | 画出一次正常请求的主链，指出状态和工具执行归属 |
| 01 | 不调工具时，框架怎样开始与结束？ | `AgentScope01DirectAnswerTest` | 真实 ReActAgent 返回文本；未订阅前工作体不运行 |
| 02 | 一个工具请求怎样变成 Java 调用？ | `StudyTools`、`ScriptedAgentScopeModel`、`AgentScope02OneToolTest` | 工具本体执行一次，下一轮模型输入包含匹配结果 |
| 03 | 两个工具、两轮推理如何衔接？ | `AgentScope03TwoToolsTest` | 两个 ID 都有结果，下一轮在整个批次处理后开始 |
| 04 | 错误与迭代上限怎样收尾？ | `AgentScope04FailureAndBudgetTest` | 分清错误值、错误信号、MAX_ITERATIONS 和额外总结 |
| 05 | 流、订阅和线程到底是什么关系？ | `TraceMiddleware`、`AgentScope05EventAndReactorTest` | 能记录事件与实际工作体的执行时机，不靠猜线程 |
| 06 | 工具为什么能被拒绝或等待确认？ | `ApprovalProbeTool`、`AgentScope06PermissionTest` | 未审批零副作用；批准后才执行，拒绝有对应结果 |
| 07 | 会话保存和中断恢复分别恢复什么？ | `AgentScope07SessionAndInterruptTest` | 新建 Agent 能加载目标会话；区分取消、中断和 pending |
| 08 | 真正的 HarnessAgent 多了什么？ | `AgentScope08HarnessWorkspaceTest` | 真实 HarnessAgent 加载临时工作区上下文，并正确关闭 |
| 09 | Plan Mode 的限制在哪里生效？ | `AgentScope09PlanModeContractTest` | 读工具可运行、写工具被拦，退出需审批；测试实际副作用 |
| 10 | Spring AI 怎样实现同一个闭环？ | `SpringAi10ToolLoopComparisonTest` | 使用真实 ChatClient/Advisor/Manager，不另写循环 |
| 11（选做） | 怎样接到自己的任务运行时？ | `AgentScopeTaskExecutor`、`MiniHarness11AgentScopeTaskTest` | 模型结果经过业务验收，已验收任务恢复后不重做 |

前 3 关读正常路径；04～07 加工程边界；08～09 才进入 AgentScope 的 Harness 层；10～11 做框架对照和业务适配。

## 三、第 00 关：建立一张够用的源码地图

### 要解决的问题

`ReActAgent.java` 很长。先认清层次，再追一条请求，不从第一行读到最后一行。

先只定位这些文件：

```text
agentscope-core/.../ReActAgent.java          运行主链与每调用 CallExecution
agentscope-core/.../agent/AgentBase.java     订阅生命周期、运行控制、会话串行
agentscope-core/.../agent/RuntimeContext.java
agentscope-core/.../state/AgentState.java    会话状态与消息上下文
agentscope-core/.../tool/Toolkit.java        工具注册与执行配置
agentscope-core/.../tool/ToolExecutor.java   校验、调度、执行与错误处理
agentscope-core/.../middleware/MiddlewareChain.java
```

### 本关写什么

新增 `00-source-map.md`，只写三项：

1. 一次用户请求的路径：公共 call 入口 → callInternal → buildAgentStream → AgentBase 生命周期 → doCall → CallExecution.doCallInner。
2. 主循环：coreAgent → executeIteration → reasoning → runPostReasoningPipeline → acting → 工具结果写回 → executeIteration。
3. 一张职责表：谁负责调用级参数、谁保存对话状态、谁管理订阅上下文、谁真正执行 Java 工具。

区别要说清：`RuntimeContext`、`AgentState` 和 Reactor Context 不是同一个对象。固定版本中，doCallInner 等核心方法位于 `CallExecution` 内部。

### 验收和面试追问

- 能不看笔记指出“工具请求在哪里写入历史”“工具结果在哪里写入历史”。
- 面试追问：为什么线程安全、会话隔离和外部工具幂等是三个不同问题？
- 暂时跳过：结构化输出、降级模型、子 Agent、完整关停和所有存储后端。

源码：[ReActAgent](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java)、[AgentBase](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/agent/AgentBase.java)。

## 四、第 01 关：先跑一次真正的 AgentScope

### 要解决的问题

没有工具调用时，AgentScope 从哪进入？没有订阅是否会执行？最终回答出现后，状态里保存了什么？

### 运行入口

附带一个独立小例子：[第一关 Maven 工程](examples/agentscope-lesson-01/pom.xml)。它不修改 MiniHarness 根 POM，不需要 Spring，不读取任何 Key。

在 MiniHarness 项目根目录执行：

```bash
mvn -f docs/examples/agentscope-lesson-01/pom.xml test
```

首次下载 Maven 依赖需要网络。测试运行过程只使用固定响应，不访问模型服务。依赖已经缓存时，可加 `-o` 验证离线运行。

如果你想亲手在 MiniHarness 的 `src/test` 中重写它，再把下面依赖加入项目 POM 的 dependencies，版本先锁定；本次文档交付没有自动修改根 POM。

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>2.0.4</version>
    <scope>test</scope>
</dependency>
```

先检查依赖树，尤其是 Reactor、Jackson、JSON Schema 相关依赖；不要为了学习第一关就批量升级整个业务项目。

### 本关完整代码

文件：[AgentScope01DirectAnswerTest.java](examples/agentscope-lesson-01/src/test/java/study/AgentScope01DirectAnswerTest.java)。如果迁到自己的测试包，调整 package 和路径。

```java
package study;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentScope01DirectAnswerTest {
    @Test
    void frameworkReturnsVisibleAnswerAfterSubscription() {
        AtomicInteger subscriptions = new AtomicInteger();
        Model scriptedModel = new Model() {
            @Override
            public String getModelName() {
                return "study-scripted-model";
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> messages,
                    List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> {
                    subscriptions.incrementAndGet();
                    assertTrue(messages.stream()
                            .anyMatch(m -> "你好".equals(m.getTextContent())));
                    return Flux.just(ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("你好，开始读源码。").build()))
                            .build());
                });
            }
        };
        ReActAgent agent = ReActAgent.builder()
                .name("study-agent")
                .sysPrompt("你是源码学习助手。")
                .model(scriptedModel)
                .maxIters(3)
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .userId("study-user").sessionId("lesson-01").build();

        Mono<Msg> pending = agent.call(List.of(new UserMessage("你好")), context);
        assertEquals(0, subscriptions.get());

        // 在测试边界等待完成；不要在生产 WebFlux 事件线程里照搬 block。
        Msg reply = pending.block(Duration.ofSeconds(5));

        assertNotNull(reply);
        assertEquals("你好，开始读源码。", reply.getTextContent());
        assertEquals(1, subscriptions.get());
        assertEquals(2, agent.getAgentState(context).getContext().size());
        assertTrue(agent.getAgentState(context).getContext().stream()
                .noneMatch(m -> m.hasContentBlocks(io.agentscope.core.message.ToolUseBlock.class)));
    }
}
```

### 怎么读这段代码

- 替换的是 `Model` 网络边界，Agent、生命周期和状态处理都是 AgentScope 自己的实现。
- `Flux.defer` 里的计数是“工作体订阅次数”。它不等于 stream 方法被调用的次数，两者需要时分别记录。
- `agent.call` 得到 Mono，测试用 block 在边界等待。不要在生产 WebFlux 事件线程中照搬 block。
- 使用显式 userId/sessionId 和 `getAgentState(context)`，不依赖无参“当前会话”访问器。

### 本关读哪些源码

- `ReActAgent.callInternal`，1012～1020 行。
- `buildAgentStream`，1038～1098 行。
- `doCallInner`，1821～1916 行；只读 pending 为空的正常分支。
- `reasoning`，2410～2539 行；先略读中间件和中断支线。
- `runPostReasoningPipeline`，2542～2598 行。
- `isFinished`，4107～4120 行。

以上行号属于固定提交，换版本要重新定位。源码链接见第 00 关。

### 通过条件

- 调用框架前没有手写外层 ReAct while。
- 订阅前计数 0，订阅后计数 1。
- 返回可见文本；目标会话历史包含用户输入和 assistant 回复。
- 能回答：无工具但只有空白文本，和无工具且有可见文本，为什么不一样？

## 五、第 02 关：一个工具，先把请求和结果配上

### 本关写什么代码

1. `StudyTools`：一个有计数器的真实本地 Java 工具 `loadQuestion(code)`，先只支持 `thread-pool`。
2. `ScriptedAgentScopeModel implements Model`：按调用步骤返回固定 ChatResponse，并记录每次模型输入快照。
3. `ToolRequestFactory`：用 AgentScope `ToolUseBlock` 构造请求。设置 id、name、input；需要原始 JSON 内容的用例也设置 content，避免把不同参数表示混在一起。
4. `AgentScope02OneToolTest`：第 0 次模型响应请求工具，第 1 次响应给最终文本。

注意导入的是 AgentScope 的注解，不是 Spring AI 同名注解：

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public static class StudyTools {
    private final AtomicInteger executions = new AtomicInteger();

    @Tool(name = "loadQuestion", description = "按编号读取线程池学习题")
    public String loadQuestion(@ToolParam(name = "code", description = "题目编号") String code) {
        if (!"thread-pool".equals(code)) {
            throw new IllegalArgumentException("只支持 thread-pool");
        }
        executions.incrementAndGet();
        return "线程池为什么先入队，再尝试创建非核心线程？";
    }
}
```

这是要写的工具骨架，需补充 AtomicInteger 导入、测试中的实例和读取计数的方法。用 `Toolkit.registerTool(toolObject)` 注册，再通过 `ReActAgent.builder().toolkit(toolkit)` 传入。

固定模型的第一步应返回 `ToolUseBlock(id="q-1", name="loadQuestion", input={code: thread-pool})`；第二步必须检查传入 messages 中已经出现 id 为 q-1 的 `ToolResultBlock`，再返回文本。不要只根据“这是第 2 次调用”无条件回答。

请求构造骨架如下。这里的类型来自 AgentScope，需补相应 imports；它不是可以执行工具的 Java 结果：

```java
Map<String, Object> input = Map.of("code", "thread-pool");
ToolUseBlock request = ToolUseBlock.builder()
        .id("q-1")
        .name("loadQuestion")
        .input(input)
        .content(JsonUtils.getJsonCodec().toJson(input))
        .build();
ChatResponse response = ChatResponse.builder()
        .content(List.of(request))
        .build();
```

### 断点和实际观察

先看 `acting`，再沿 executeToolCalls → dispatchToolCalls → Toolkit.callTools → ToolExecutor.executeAll → executeCore → 工具本体。
回程看 `notifyPostActingHook` 写历史，再看 executeIteration(iter + 1)。完整函数之间存在返回和回调，不要把所有相邻阶段画成直接 Java 调用。

通过条件：工具本体计数 1；模型订阅 2 次；第二次模型输入含 q-1 对应的结果；最终有可见文本。

面试追问：模型知道工具名，是否等于它可以直接运行 Java 方法？同名工具调用两次为什么必须有两个 ID？

源码：[Toolkit](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/tool/Toolkit.java)、[ToolExecutor](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/tool/ToolExecutor.java)。

## 六、第 03 关：两个工具，搞清一轮到底怎么算

### 本关写什么代码

新增 `loadRubric(code)`，让固定模型第一轮同时返回两个请求：

```text
q-1 -> loadQuestion(thread-pool)
r-1 -> loadRubric(thread-pool)
```

新增 `AgentScope03TwoToolsTest`。先配置串行工具执行，跑通后再增加一个并行对照测试。工具采用只读的计数/固定返回，不引入数据库和远程 API。

串行配置入口是 `new Toolkit(ToolkitConfig.builder().parallel(false).build())`。然后注册两个工具，把这个 Toolkit 交给 ReActAgent；不要在框架外再执行一次工具。

第一版的实际历史应该能对应：

```text
用户输入
assistant：q-1 和 r-1 两个 ToolUseBlock
q-1 对应的 ToolResultBlock
r-1 对应的 ToolResultBlock
assistant：最终答案
```

这是一条正常、无中间件改写的教学路径。不要要求所有分支的消息数量、分组与该例完全相同；关键是请求与结果的关联。

### 必须写的断言

- 两个工具本体分别执行一次。
- 第二轮模型输入中，q-1 和 r-1 各有一个对应结果。
- 没有在结果写回之前调用第二轮模型。
- 模型正常订阅两次，而不是“一个工具消耗一轮”。
- 并行对照中记录 start/end，而不是仅按结果列表判断实际完成顺序。

并行实验里，用 `CountDownLatch` 或受控的 Reactor 信号协调，避免把任意 sleep 当可靠测试。所有等待都要有超时，并在 finally 中释放信号。

### 本关读哪些源码

`acting` 2858～2987 行、`notifyPostActingHook` 3660～3708 行，以及 ToolExecutor.executeAll 的完整重载 374～434 行。
重点比较 `Flux.concat` 与 `Flux.mergeSequential`。后者可以提前订阅但保持结果输出顺序，输出顺序不等于副作用顺序。

当前版本有一个值得自己确认的注释/代码差异：executeAll 的注释描述未知旧式工具串行，但 isConcurrencySafe 对非 ToolBase 实际返回 true。以代码为准。

面试追问：为什么上层可以按索引把请求与结果配起来？如果改成乱序 merge，上层还安全吗？

## 七、第 04 关：失败、空回复和迭代上限

### 本关写什么代码

新增 `AgentScope04FailureAndBudgetTest`，拆成独立测试，别把所有失败混在一个大方法里。

| 测试方法建议 | 你要制造的输入 | 必须检查的结果 |
| --- | --- | --- |
| `invalidArgumentsDoNotReachBusinessBody` | 缺少必填参数或不合法类型 | 工具业务计数为 0，观察校验/转换错误发生在哪层 |
| `unknownToolProducesAnObservation` | 模型请求未注册名字 | 记录错误结果及其请求 ID，不先猜具体错误文案 |
| `toolFailureCanReachNextReasoning` | 工具返回错误信号 | 检查框架转换后的错误结果进入下一轮输入 |
| `emptyVisibleAnswerDoesNotFinishNormally` | 无工具，只有空白文本或 ThinkingBlock | 普通终止条件不满足，观察提醒和预算分支 |
| `maxIterationsMayTriggerSummary` | maxIters=1，第一轮仍请求工具 | 工具完成后尝试总结，结果原因是 MAX_ITERATIONS |

固定响应脚本要包含总结那一步。否则脚本耗尽产生的测试失败，可能被误认成 AgentScope 的预算处理错误。

在不增加模型重试、降级和额外中间件的上述固定场景里，maxIters=1 可能对应“1 次普通推理 + 1 次总结模型调用”。不要把 maxIters 当整个请求的绝对模型调用次数。

### 要读的分支

- ToolExecutor.executeCore：查找工具、可用性、参数处理、错误转结果。
- executeWithInfrastructure、applyTimeout、applyRetry：错误信号是否还能到达重试器。
- reasoning 的预算检查、summarizing 和 handleSummaryError。

**错误结果是一个正常值；Reactor onError 是错误信号。** 先被转成 ToolResultBlock.error 的失败，不能再想当然地触发 retryWhen。

本关先不自己实现重试系统。选做一个独立 Reactor 小测试，把 onErrorResume 放在 retryWhen 前后，比较工作体执行次数。

面试追问：超时后重试为什么可能重复产生副作用？返回 MAX_ITERATIONS 是否等于业务任务成功？

## 八、第 05 关：把事件、订阅时机与线程拆开看

### 本关写什么代码

1. `TraceMiddleware implements MiddlewareBase`：先只覆盖 onReasoning 和 onActing，记录会话、阶段、开始/完成/错误，不改写结果。
2. `AgentScope05EventAndReactorTest`：用 `agent.streamEvents(messages, context)` 收集事件，关联工具 ID。
3. 一个独立的 `ReactorTimingTest`：只验证 defer、then、flatMap 和 subscribeOn，不接真实模型。

Middleware 的形状以固定源码为准，例如：

```java
public Flux<AgentEvent> onReasoning(
        Agent agent, RuntimeContext context, ReasoningInput input,
        Function<ReasoningInput, Flux<AgentEvent>> next) {
    return Flux.defer(() -> {
        // 在这里记录“订阅期开始”，不是在构造方法时打一个伪开始日志。
        return next.apply(input)
                .doOnComplete(() -> { /* 记录正常完成 */ })
                .doOnError(error -> { /* 记录错误 */ });
    });
}
```

上面是需补齐导入、日志和测试断言的扩展骨架，尚未作为完整 Demo 验证。通过 builder.middleware(...) 注册。onActing 的 input 是工具批次，不能凭接口注释推断“一定每个工具各拦截一次”。

### 记录格式

```text
sessionId, iteration(教学记录), phase, toolCallId, threadName, timestamp
```

iteration 若由自己的 Trace 计数得出，要注明这是教学计数，不冒充框架事件自带字段。

### 通过条件

- 能在模型块、工具结果事件、最终 AgentResultEvent 中找出不同职责。
- 不要求随机 replyId、随机 blockId 的字面值固定，只检查关联关系。
- 能证明 then 后读取累积状态时为什么需要 defer。
- 能指出 subscribeOn 不会搬走此前在 Java 方法体中已经执行的同步工作。
- 能说明客户端取消订阅不等于外部工具操作回滚。

面试追问：手动 subscribe 为什么需要额外处理 Context 传播与取消？如果消费者很慢，BUFFER 策略可能带来什么？

源码：[MiddlewareBase](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/middleware/MiddlewareBase.java)、[工具并发测试参考](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/test/java/io/agentscope/core/tool/ReActAgentToolkitConcurrencyTest.java)。

## 九、第 06 关：执行前授权，与等用户批准

### 本关写什么代码

- `ApprovalProbeTool extends ToolBase`：用 AtomicInteger 记录“模拟写操作”，不写真实业务数据。
- 重写 `checkPermissions` 与 `callAsync`，分别构造 ALLOW、DENY、ASK 场景。
- 用 `Toolkit.registerAgentTool(...)` 注册 ToolBase；普通注解工具的 registerTool 是另一种入口。
- `AgentScope06PermissionTest`：测试批准、拒绝、缺失确认三条路径。

构造 ToolBase 和确认消息时，直接参照上游 `ReActAgentHitlTest` 的 AskingTool 与 confirmMsg，逐项理解参数，别凭印象补构造器。

### 要跑出的事实

1. ASK 时返回 PERMISSION_ASKING，出现 RequireUserConfirmEvent，工具副作用计数仍为 0。
2. 调用者按待确认的 tool_call 构造 ConfirmResult，放入 Msg.METADATA_CONFIRM_RESULTS，再发起下一次调用。
3. 批准后才执行工具；拒绝时不执行，但有对应请求的拒绝结果。
4. 缺失、错配或重复的确认，不应该被你自己的包装代码无条件放行。

不要只验证“提示词里写了禁止修改”。验证实际业务计数和结果状态。也不要把“模型没看到某个 Schema”当作执行授权已经落实。

同批次同时有 ASK 和 ALLOW 时，固定版本的 actingStream 会走暂停路径；需要专门观察，不能先假定 ALLOW 已经执行。

面试追问：为什么暂停属于协议状态，而不是简单抛一个 RuntimeException？审批通过后如何避免批准错了另一条同名工具调用？

源码：[HITL 测试](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/test/java/io/agentscope/core/agent/ReActAgentHitlTest.java)。

## 十、第 07 关：会话、持久化、中断与恢复

### 本关写什么代码

新增 `AgentScope07SessionAndInterruptTest`，至少拆开四种情形：

| 情形 | 写法与检查 |
| --- | --- |
| 会话隔离 | 相同 Agent，两个独立 RuntimeContext。分别断言各自 AgentState 的内容，不使用无参 state 访问器判断并发调用 |
| 进程边界前的持久化模拟 | 在 @TempDir 中创建 JsonFileAgentStateStore；一次调用结束后，新建 Store 和 Agent，再用相同 userId/sessionId 读取 |
| 用户显式中断 | 用受控模型流/工具信号进入窗口后调用 interrupt(context)，观察 INTERRUPTED、pending 结果与保存边界 |
| 外部工具 suspended | 按 tool_call ID 补交 ToolResultBlock；观察与显式中断不同的恢复分派 |

本地状态存储入口可从这段开始：

```java
AgentStateStore store = new JsonFileAgentStateStore(tempDir.resolve("agent-state"));
ReActAgent agent = ReActAgent.builder()
        .name("study-agent")
        .model(scriptedModel)
        .stateStore(store)
        .build();
```

这是构造骨架，需补导入、@TempDir 和固定模型；不要在测试中使用无参 JsonFileAgentStateStore 默认写到用户目录。

### 重点读的源码

- doCallInner：新调用、ASK、pending recovery 与外部结果的分派。
- saveStateToSession：有无 store，以及保存失败如何向调用方表现。
- handleInterrupt 与 synthesizeErrorResultsForPendingToolCalls：为什么先补工具结果，再追加恢复消息。
- resumeAgent：这里是 acting(0)，并非恢复旧线程的 Java 栈。

### 通过条件

- 普通保存后，新建实例能读回指定会话，另一会话不混入内容。
- 报告明确写“新建对象重新加载”，不把它夸大成已经验证 kill -9 崩溃恢复。
- 分清 cancel、用户 interrupt、系统 shutdown、权限 ASK 和外部 suspended。
- 中断后没有简单盲目重放所有工具；按实际 pending 配对与恢复策略解释。
- 能讲清：消息一致性不能保证外部操作 exactly-once；看到 INTERRUPTED 回复也未必证明保存成功。

面试追问：工具已经执行，但保存结果前进程退出，框架凭什么知道是否能重做？

源码：[会话测试](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/test/java/io/agentscope/core/agent/ReActAgentPerSessionStateTest.java)。

## 十一、第 08 关：开始使用真正的 HarnessAgent

完成前面正常路径和状态边界后，再加入 test scope 的 `io.agentscope:agentscope-harness:2.0.4`。不要为第一关提前引入所有扩展与服务模块。

### 本关写什么代码

新增 `AgentScope08HarnessWorkspaceTest`：

1. 用 @TempDir 建临时工作区。
2. 写入带唯一标记的 AGENTS.md，例如 `STUDY_WORKSPACE_MARKER_001`。
3. 使用真实 `io.agentscope.harness.agent.HarnessAgent`，设置 model 和 workspace。
4. 固定模型记录实际 messages，断言工作区标记真的进入输入；最后返回普通文本。
5. 用 try-with-resources 关闭 HarnessAgent。

上游已有实际用法，核心构造形状如下；RuntimeContext、消息和 Model 仍沿用前面学过的类型：

```java
try (HarnessAgent agent = HarnessAgent.builder()
        .name("study-harness")
        .model(scriptedModel)
        .workspace(workspace)
        .build()) {
    Msg reply = agent.call(new UserMessage("读取工作区规则"), context)
            .block(Duration.ofSeconds(5));
    // 断言 reply，以及 scriptedModel 实际收到的工作区标记。
}
```

上面是尚未代你执行的后续练习骨架，需要新增 harness 依赖并补齐 imports。它与第一关 ReActAgent 的 call 重载不同，以各自类的声明为准。

### 通过条件

- 实例类型确实是 HarnessAgent，而不是自己创建的 MiniReActHarness。
- 能通过实际模型输入证明 workspace 规则注入，不只断言文件存在。
- 能指出哪些是 core Agent 的能力，哪些是 harness 模块组装进来的能力。
- 正确释放资源；不把上游测试专用的 HarnessQuiescence 扩展当作对外生产 API 引入。

面试追问：工作区文件、模型记忆和任务状态分别由谁管理？为什么不是把所有文本都永久塞入 system prompt？

源码：[HarnessAgent 集成示例](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/test/java/io/agentscope/harness/agent/HarnessAgentIntegrationExampleTest.java)。

## 十二、第 09 关：Plan Mode 的真实执行限制

这一关才读下面三个文件，前半程不要提前把它们混入 ReAct 主循环：

- `PlanModeManager`：enter、exit、writePlan 与 AgentState.planModeContext。
- `PlanModeTools`：plan_enter、plan_write、plan_exit。
- `PlanModeMiddleware.onActing`：按工具可读性/允许列表分组，给被拦工具生成 DENIED 结果。

### 本关写什么代码

新增 `AgentScope09PlanModeContractTest`，用真实 HarnessAgent 或按上游测试方式组装真实 Plan Mode 组件；不要自行实现一个 boolean 后声称验证了框架。

工具只用两个安全探针：一个返回文本的读工具，一个只增加 AtomicInteger 的“模拟写”工具。将读探针声明为只读（注解入口可使用 readOnly=true），写探针保持非只读，并检查实际 readOnlyResolver 的判定。工作区仍放 @TempDir。

按以下顺序构造固定模型响应和断言：

1. 调用 plan_enter，检查实际 PlanModeContextState 激活。
2. 请求读工具，观察正常结果。
3. 请求模拟写工具，检查本体计数没有增加，并检查 DENIED 结果的 ID。
4. 通过 plan_write 写计划，检查返回的 workspace 相对路径及文件内容，不猜随机默认文件名。
5. 请求 plan_exit，检查等待批准；拒绝后仍留在规划模式。
6. 再次提出退出并批准，检查模式已退出，之后才能允许模拟写探针按权限运行。

plan_exit 的 checkPermissions 返回 ASK；批准后 callAsync 才执行 manager.exit。请沿权限调用链观察，别只读“退出模式”的提示词。

### 必须写下的一段辨析

在自己的学习记录里回答：

- ReAct：每轮根据模型输出选择工具/回答，反馈结果后继续。
- Plan Mode：规划阶段的交互与工具约束模式，本版本如何保存模式状态、怎样审批退出。
- Plan-and-Execute：规划与执行的架构分工；若要声称存在独立 Planner、Executor、Replanner，必须继续找到具体类型、调用关系与测试，不能从 plan 工具名推断。

这份路线不宣称已经完成整个仓库的 Planner/Executor/Replanner 存在性审计，也不把 todo 列表自动等同于持久任务图。

源码：[PlanModeManager](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/main/java/io/agentscope/harness/agent/workspace/plan/PlanModeManager.java)、[PlanModeTools](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/PlanModeTools.java)、[PlanModeMiddleware](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/PlanModeMiddleware.java)。

## 十三、第 10 关：用 Spring AI 2.0 做同题对照

### 本关写什么代码

新增 `SpringAi10ToolLoopComparisonTest`。实现一个固定响应的 Spring AI ChatModel 测试替身，接到真实 ChatClient。工具通过 Spring AI 自己的 ToolCallback/工具注解注册。

先只测试同步路径：第一轮返回工具请求，第二轮检查工具响应消息，再给文本。

必须使用框架自己的工具循环：

```text
真实 ChatClient
  -> 真实 ToolCallingAdvisor
  -> ChatModel 测试替身
  -> 真实 ToolCallingManager / ToolCallback
  -> 带工具结果进入下一轮
```

不要为了让断言通过，自己在外层写 while 去替代本关的被测对象。

第二个测试才是“用户控制模式”：直接调用 ChatModel，或显式禁用 ChatClient 的工具 Advisor 自动注册，再由自己控制每轮。把这两个测试分开命名，避免重复驱动。

### 需要验证的差别

| 对照点 | 你要记录什么 |
| --- | --- |
| 入口 | AgentScope ReActAgent 与 Spring AI ChatClient 的实际调用栈 |
| 循环归属 | CallExecution.reasoning/acting 与 ToolCallingAdvisor.adviseCall |
| 每轮扩展 | Middleware 与 Advisor；测试外层一次、内层每轮的执行次数 |
| 结束条件 | AgentScope 的可见文本判定与 Spring AI 默认 hasToolCalls 判定，不强求行为完全相同 |
| 预算 | AgentScope maxIters 与总结；Spring AI 2.0.0 的预算扩展如何实现 |
| 工具并发 | 不把模型的 parallelToolCalls 选项当作 Java 端已经并行 |
| 状态 | 对话上下文与业务任务状态分别由谁负责 |

2.0 的重要更正：ChatModel 的旧式内部工具循环已经迁移到 ToolCallingAdvisor。不要照搬 1.x 的 internalToolExecutionEnabled 开关。
原生 Tool Call Confirmation 的文档标明从 2.1.0 开始，不能当作 2.0 关卡的自带能力。

通过条件：同题在两个真实框架中跑通，比较模型输入、工具次数和停止原因；差异有源码证据，不只比 API 写法。

源码：[Spring AI 2.0.0 ToolCallingAdvisor](https://github.com/spring-projects/spring-ai/blob/v2.0.0/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/ToolCallingAdvisor.java)、[DefaultToolCallingManager](https://github.com/spring-projects/spring-ai/blob/v2.0.0/spring-ai-model/src/main/java/org/springframework/ai/model/tool/DefaultToolCallingManager.java)、[工具循环说明](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html)、[2.1 审批功能的版本边界](https://docs.spring.io/spring-ai/reference/2.1/api/tools/tool-call-confirmation.html)。

## 十四、第 11 关（选做）：接入你已有的 MiniHarness

现在才写生产包中的薄适配器，不改写 HarnessOrchestrator。

### 要写的代码

- `AgentScopeTaskExecutor implements TaskExecutor`：从 TaskExecutionContext 取任务输入，调用真实 AgentScope，把停止原因转换成 TaskExecutionResult。
- `MiniHarness11AgentScopeTaskTest`：至少两个任务，先 answer，再 report，产物写 @TempDir。
- 一个独立业务 Verifier：除了文件存在，还检查必需字段、来源或题目评分点。

### 验收要求

1. 正常最终答案产生实际 Artifact，Verifier 通过后任务才 ACCEPTED。
2. MAX_ITERATIONS、PERMISSION_ASKING、TOOL_SUSPENDED 不被一律当成成功答案。
3. 目前 TaskExecutionResult 只有成功/失败等现有结构；如果要接入等待审批，先设计与 Mission WAITING/恢复入口的映射，不能随便 return success("待审批")。
4. 保存任务检查点后，新建 Store/Orchestrator，已验收任务不重复执行，后续任务读取已有产物。
5. AgentStateStore 保存的是 Agent 协议状态，MiniHarness 的检查点保存的是业务任务进度；明确二者事务边界和可能不一致的窗口。

面试追问：为什么模型说“完成了”，还要经过 Verifier？任务级重试怎样与工具幂等配合？

这关是适配练习，不要求你重造多存储后端、分布式调度、完整权限引擎或所有关闭恢复策略。

## 十五、建议的学习文件组织

第一关独立样例已经放在 docs/examples，方便不改项目依赖就先验证框架。后续由你自己逐步在测试目录实现：

```text
src/test/java/com/chinazhouwy/miniharness/study/
  agentscope/
    support/ScriptedAgentScopeModel.java
    support/StudyTools.java
    support/ToolRequestFactory.java
    AgentScope02OneToolTest.java
    AgentScope03TwoToolsTest.java
    AgentScope04FailureAndBudgetTest.java
    AgentScope05EventAndReactorTest.java
    AgentScope06PermissionTest.java
    AgentScope07SessionAndInterruptTest.java
    AgentScope08HarnessWorkspaceTest.java
    AgentScope09PlanModeContractTest.java
  springai/
    SpringAi10ToolLoopComparisonTest.java
  integration/
    MiniHarness11AgentScopeTaskTest.java
```

先把真实测试都放 src/test。只有最后一个真正要复用的适配器，才考虑移到 src/main。固定模型、计数探针和断言工具不要混入业务核心。

## 十六、每一关只留一张复盘卡

完成后按下面格式记录，不抄大段源码：

```text
本关问题：
框架与版本：
源码入口/关键分支：
我新增的类与测试：
固定输入与模型响应脚本：
我预期什么：
实际发生什么：
模型/工具工作体分别执行几次：
停止原因与状态变化：
哪项结论只是推断，尚未验证：
如果换成真实外部副作用，还缺什么：
我能否用三分钟讲清楚：
```

可以解释还不算通过。至少运行一个正例和一个能暴露错误假设的反例，再进入下一关。

## 十七、这份交付已经验证了什么

- 已按 AgentScope v2.0.4 固定源码核对本文使用的核心方法、Model 接口、Toolkit 注册入口、RuntimeContext、HarnessAgent workspace 用法及 Plan Mode 的主要拦截点。
- 第一关完整代码已在独立 Maven 工程中，使用真正的 agentscope-core 2.0.4 运行通过：1 个测试、0 失败、0 错误、0 跳过。
- 第一关用的是固定响应模型，没有访问真实模型服务；不代表供应商 SDK、真实工具或所有上游测试都通过。
- 第一关后面的代码是分关作业和扩展骨架，尚未代你实现并运行；不把这份路线等同于已完成全部关卡。
- 根 POM、现有业务源码和此前未提交修改均不在本次文档变更范围。

验证记录：[第一关验收记录](examples/agentscope-lesson-01/VALIDATION.md)。现在先做 01、02、03；能从真实消息和断点解释一轮之后，再进入 04。
