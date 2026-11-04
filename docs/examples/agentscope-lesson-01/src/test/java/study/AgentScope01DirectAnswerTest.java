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
