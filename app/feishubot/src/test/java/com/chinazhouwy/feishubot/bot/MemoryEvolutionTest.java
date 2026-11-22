package com.chinazhouwy.feishubot.bot;

import com.chinazhouwy.feishubot.monitor.AgentEventStore;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryEvolutionTest {
    @TempDir
    Path temp;

    @Test
    void processesPersistedTurnsAndLoadsAllFourFilesForTheSameChat() {
        Path root = temp.resolve("evolution");
        var events = new AgentEventStore(temp.resolve("logs"));
        Model model = mock(Model.class);
        var first = new MemoryEvolution(new AgentWorkspace(root), events, model);
        first.record("main", "chat-1", "trace-1", "以后回答我先给结论", "收到");

        var restarted = new AgentWorkspace(root);
        var worker = new MemoryEvolution(restarted, events, model);
        worker.processPending(Instant.now().minusSeconds(60));
        verifyNoInteractions(model);

        when(model.stream(anyList(), eq(List.of()), any(GenerateOptions.class))).thenReturn(answer("""
                {"AGENTS.md":"执行完先检查工具状态","SOUL.md":"你是小飞",
                 "USER.md":"以后回答先给结论","MEMORY.md":"天气任务已停用"}
                """));
        worker.processPending(Instant.now());

        assertThat(restarted.systemPrompt("main", "chat-1"))
                .contains("执行完先检查工具状态", "你是小飞", "以后回答先给结论", "天气任务已停用");
        assertThat(restarted.systemPrompt("main", "chat-2")).doesNotContain("以后回答先给结论");
        assertThat(root.resolve("pending")).isEmptyDirectory();
    }

    @Test
    void rejectsUpdatesOutsideTheFourFilesAndRetriesTheSameTurn() {
        var workspace = new AgentWorkspace(temp.resolve("evolution"));
        Model model = mock(Model.class);
        when(model.stream(anyList(), eq(List.of()), any(GenerateOptions.class)))
                .thenReturn(answer("{\"USER.md\":\"非法批次\",\"../settings.json\":\"改配置\"}"),
                        answer("{\"USER.md\":\"先给结论\"}"));
        var worker = new MemoryEvolution(workspace, new AgentEventStore(temp.resolve("logs")), model);
        worker.record("main", "chat-1", "trace-1", "以后先给结论", "收到");

        worker.processPending(Instant.now());
        assertThat(workspace.pendingDirectory()).isNotEmptyDirectory();
        assertThat(workspace.systemPrompt("main", "chat-1")).doesNotContain("非法批次");

        worker.processPending(Instant.now());
        assertThat(workspace.pendingDirectory()).isEmptyDirectory();
        assertThat(workspace.systemPrompt("main", "chat-1")).contains("先给结论");
    }

    private static Flux<ChatResponse> answer(String text) {
        return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).build());
    }
}
