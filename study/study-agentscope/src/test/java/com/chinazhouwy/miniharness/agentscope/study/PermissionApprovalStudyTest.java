package com.chinazhouwy.miniharness.agentscope.study;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.*;
import reactor.core.publisher.Flux;
import java.util.stream.Collectors;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 权限在执行侧生效：模型提出请求不等于 Java 工具获得执行权。 */
class PermissionApprovalStudyTest {
    public static class TicketWriter {
        int writes;
        @Tool(name = "close_ticket", description = "关闭演示工单")
        public String closeTicket() { writes++; return "已关闭 DEMO-001"; }
    }

    /** 同一个请求分别配置 ALLOW/DENY，打印实际副作用次数，观察权限的作用。 */
    @Test @DisplayName("权限：ALLOW 执行一次，DENY 执行零次")
    void allowAndDeny() {
        for (var behavior : List.of(PermissionBehavior.ALLOW, PermissionBehavior.DENY)) {
            TicketWriter writer = new TicketWriter();
            try (var agent = agent(writer, behavior)) {
                var answer = agent.call("关闭工单").block(Duration.ofSeconds(10));
                System.out.println("[权限] " + behavior + " 实际写次数=" + writer.writes + " 回答=" + answer.getTextContent());
            }
        }
    }

    /** 模拟用户批准／拒绝，不读控制台；审批结果绑定原始 ToolUseBlock，沿用会话并创建新 run。 */
    @Test @DisplayName("人工确认：等待时无副作用，批准后执行，拒绝后不执行")
    void approveAndReject() {
        for (boolean approved : List.of(true, false)) {
            TicketWriter writer = new TicketWriter();
            try (var agent = agent(writer, PermissionBehavior.ASK)) {
                RuntimeContext context = RuntimeContext.builder().userId("demo").sessionId("session-" + approved).build();
                var events = agent.streamEvents("关闭工单", context).collectList().block(Duration.ofSeconds(10));
                var request = events.stream()
                                    .filter(RequireUserConfirmEvent.class::isInstance)
                                    .map(RequireUserConfirmEvent.class::cast)
                                    .findFirst()
                                    .orElseThrow();
                System.out.println("[等待确认] " + request.getToolCalls().getFirst().getId() + " 写次数=" + writer.writes);
                Msg decision = UserMessage.builder()
                        .textContent("用户批复")
                        .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(approved, request.getToolCalls().getFirst()))))
                        .build();
                var next = RuntimeContext.builder().userId("demo").sessionId("session-" + approved).build();
                var answer = agent.call(List.of(decision), next).block(Duration.ofSeconds(10));
                System.out.println("[批复] approved=" + approved + " 写次数=" + writer.writes + " 回答=" + answer.getTextContent());
            }
        }
    }

    private ReActAgent agent(TicketWriter writer, PermissionBehavior behavior) {
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(writer);
        var rule = new PermissionRule("close_ticket", null, behavior, "study");
        var permissions = PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        switch (behavior) {
            case ALLOW -> permissions.addAllowRule("close_ticket", rule);
            case DENY -> permissions.addDenyRule("close_ticket", rule);
            case ASK -> permissions.addAskRule("close_ticket", rule);
        }
        return ReActAgent.builder().name("approval-study").toolkit(toolkit)
                .model(toolModel()).permissionContext(permissions.build()).build();
    }

    /** 首轮提出指定工具请求，观察到真实工具结果后原样汇总；不预先假设执行成功。 */
    private Model toolModel() {
        return new Model() {
            @Override public String getModelName() { return "offline-close-ticket"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                var results = messages.stream().flatMap(m -> m.getContent().stream())
                        .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast).toList();
                if (results.isEmpty()) {
                    return Flux.just(ChatResponse.builder().content(List.of(ToolUseBlock.builder()
                            .id("call-close-ticket").name("close_ticket").input(Map.of()).build())).build());
                }
                String text = results.stream()
                                    .map(r -> r.getState() + ":" + r.getOutput().stream()
                                    .filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast)
                                    .map(TextBlock::getText)
                                    .collect(Collectors.joining("\n")))
                                    .collect(Collectors.joining("\n"));
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).build());
            }
        };
    }
}
