package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleTaskToolTest {
    @TempDir
    Path temp;

    @Test
    void chatToolRegistersATaskAndPassesTheInstructionToTheCallback() throws Exception {
        Bot bot = BotRegistry.MAIN;
        var model = OpenAIChatModel.builder()
                .apiKey("test-key")
                .baseUrl("https://example.invalid/v1")
                .modelName("test-model")
                .build();
        var factory = new AgentFactory(model, temp.resolve("state").toString(), new FeishuTools(),
                new DeepSeekWebTools("test-key"), new AgentEventStore(temp.resolve("events")));
        var triggered = new CompletableFuture<Run>();

        try (var scheduler = new BotTaskScheduler();
             var chatAgent = factory.create(bot, new ScheduleTaskTool(scheduler, bot.id(),
                     (task, instruction) -> triggered.complete(new Run(task, instruction))));
             var scheduledAgent = factory.create(bot)) {
            assertThat(scheduledAgent.getToolkit().getToolNames()).doesNotContain("schedule_task");

            var toolkit = chatAgent.getToolkit();
            assertThat(call(toolkit, "invalid cron", "查天气").getState()).isEqualTo(ToolResultState.ERROR);
            assertThat(scheduler.list()).isEmpty();

            var result = call(toolkit, "* * * * * *", "查天气");
            var run = triggered.get(5, TimeUnit.SECONDS);
            assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
            assertThat(result.getOutput().toString()).contains(run.task().taskId());
            assertThat(run.task().botId()).isEqualTo(bot.id());
            assertThat(run.task().zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
            assertThat(run.instruction()).isEqualTo("查天气");
            assertThat(scheduler.list()).containsExactly(run.task());
        }
    }

    private ToolResultBlock call(Toolkit toolkit, String cron, String instruction) {
        Map<String, Object> input = Map.of("cron", cron, "instruction", instruction);
        var toolUse = ToolUseBlock.builder()
                .name("schedule_task")
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
        return toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolUse).input(input).build())
                .block(Duration.ofSeconds(5));
    }

    private record Run(SchedTask task, String instruction) {}
}
