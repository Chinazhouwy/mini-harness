package com.chinazhouwy.miniharness.runtime.strategy;

import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.event.HarnessEvent;
import com.chinazhouwy.miniharness.runtime.model.ModelRequest;
import com.chinazhouwy.miniharness.runtime.workflow.NodeHandler;
import com.chinazhouwy.miniharness.runtime.workflow.NodeInput;
import com.chinazhouwy.miniharness.runtime.workflow.NodeOutput;
import com.chinazhouwy.miniharness.runtime.workflow.NodeSpec;
import com.chinazhouwy.miniharness.runtime.workflow.NodeTypeRegistry;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowExecutionResult;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「咨询模型」类策略的公共骨架：把选定节点包一层模型复核装饰器，
 * 再交由标准执行器执行——超时/重试/预算/事件语义与主链路完全一致。
 *
 * Harness 管控边界：模型回复只进入事件审计（attributes），绝不写入节点输出数据；
 * 节点输出永远来自确定性处理器，模型无权修改计算结果。
 */
public abstract class ModelConsultingStrategy implements ExecutionStrategy {

    /** 该节点是否需要咨询模型（策略差异所在）。 */
    protected abstract boolean shouldConsult(NodeSpec spec);

    /** 模型咨询的目的说明（进入提示词与事件）。 */
    protected abstract String consultPurpose();

    @Override
    public final StrategyResult execute(StrategyRequest request) {
        long start = System.nanoTime();
        AtomicLong promptChars = new AtomicLong();
        NodeTypeRegistry decorated = new NodeTypeRegistry();
        int consultedNodes = 0;
        for (NodeSpec spec : request.definition().nodes()) {
            NodeHandler original = request.registry().require(spec.type());
            if (decorated.has(spec.type())) {
                // 注册表按类型分发；同类型节点共享同一装饰器/原处理器。
                continue;
            }
            if (shouldConsult(spec)) {
                decorated.register(new ModelConsultingHandler(
                        original, spec, request.context(), promptChars, consultPurpose()));
                consultedNodes++;
            } else {
                decorated.register(original);
            }
        }
        WorkflowExecutionResult execution = StrategySupport.execute(request, decorated);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        return StrategySupport.result(this, StrategySupport.mergeOutputs(execution),
                request.context().budget(), promptChars.get(), durationMs,
                consultPurpose() + "；咨询节点数 " + consultedNodes + "/" + request.definition().nodes().size());
    }

    /** 模型咨询装饰器：先问模型（扣预算/发事件），再委托确定性处理器。 */
    static final class ModelConsultingHandler implements NodeHandler {

        private final NodeHandler original;
        private final NodeSpec spec;
        private final com.chinazhouwy.miniharness.runtime.workflow.NodeContext context;
        private final AtomicLong promptChars;
        private final String purpose;

        ModelConsultingHandler(NodeHandler original, NodeSpec spec,
                               com.chinazhouwy.miniharness.runtime.workflow.NodeContext context,
                               AtomicLong promptChars, String purpose) {
            this.original = original;
            this.spec = spec;
            this.context = context;
            this.promptChars = promptChars;
            this.purpose = purpose;
        }

        @Override
        public String type() {
            // 与原节点同类型：执行器按 NodeSpec.type 分发到本装饰器。
            return spec.type();
        }

        @Override
        public NodeOutput execute(NodeInput input) throws Exception {
            // 预算扣减是 Harness 行为；无模型部署（NoopModelPort）在此显式失败，绝不静默跳过。
            context.budget().chargeModelCall();
            String upstreamSummary = summarize(input);
            String user = purpose + "\n节点: " + spec.id() + " (type=" + spec.type() + ")\n"
                    + "上游数据摘要: " + upstreamSummary + "\n"
                    + "请复核该节点的输入与执行安排；你无权修改确定性计算结果。";
            promptChars.addAndGet(user.length());
            context.events().publish(HarnessEvent.builder(EventType.MODEL_CALLED)
                    .task(context.taskId()).run(context.runId()).node(input.nodeId())
                    .workflow(null)
                    .message("策略咨询模型: 节点 " + spec.id())
                    .payload(Map.of("strategyPurpose", purpose, "nodeType", spec.type())));
            var reply = context.model().complete(new ModelRequest(
                    "你是执行复核助手，只做简短复核说明，不产生业务数字。", user, Map.of()));
            context.events().publish(HarnessEvent.builder(EventType.MODEL_REPLIED)
                    .task(context.taskId()).run(context.runId()).node(input.nodeId())
                    .message("模型复核返回（仅审计，不进入节点输出）")
                    .payload(Map.of("replyChars", reply.content().length())));
            // 模型回复只存运行内暂存供审计；节点输出完全由确定性处理器决定。
            context.attributes().put("modelNote:" + spec.id(), reply.content());
            return original.execute(input);
        }

        /** 上游数据摘要（键与短值截断），控制提示词长度。 */
        private String summarize(NodeInput input) {
            StringBuilder sb = new StringBuilder();
            for (var entry : input.upstreamOutputs().entrySet()) {
                sb.append(entry.getKey().value()).append("=");
                Map<String, Object> data = entry.getValue().data();
                if (data.isEmpty()) {
                    sb.append("{}");
                } else {
                    sb.append(data.keySet()).append("(样本:")
                            .append(String.valueOf(data.values().iterator().next()), 0, Math.min(60,
                                    String.valueOf(data.values().iterator().next()).length()))
                            .append(")");
                }
                sb.append("; ");
            }
            sb.append("workflowInput=").append(input.workflowInput().keySet());
            return sb.toString();
        }
    }
}
