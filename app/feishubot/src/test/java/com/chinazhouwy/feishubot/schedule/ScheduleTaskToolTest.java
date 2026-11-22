package com.chinazhouwy.feishubot.schedule;

import com.chinazhouwy.feishubot.bot.AgentFactory;
import com.chinazhouwy.feishubot.bot.AgentWorkspace;
import com.chinazhouwy.feishubot.bot.Bot;
import com.chinazhouwy.feishubot.bot.BotRegistry;
import com.chinazhouwy.feishubot.monitor.AgentEventStore;
import com.chinazhouwy.feishubot.tool.DeepSeekWebTools;
import com.chinazhouwy.feishubot.tool.FeishuTools;

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
    void chatToolSavesTheTaskAndPassesItToTheCallback() throws Exception {
        Bot bot = BotRegistry.MAIN;
        var model = OpenAIChatModel.builder()
                .apiKey("test-key")
                .baseUrl("https://example.invalid/v1")
                .modelName("test-model")
                .build();
        var factory = new AgentFactory(model, temp.resolve("state").toString(), new FeishuTools(),
                new DeepSeekWebTools("test-key"), new AgentEventStore(temp.resolve("events")), new AgentWorkspace());
        var triggered = new CompletableFuture<SchedTask>();
        Path file = temp.resolve("scheduled-tasks.json");

        try (var scheduler = new BotTaskScheduler(file);
             var chatAgent = factory.create(bot, new ScheduleTaskTool(scheduler, bot.id(), "chat-1",
                     triggered::complete));
             var scheduledAgent = factory.create(bot)) {
            assertThat(scheduledAgent.getToolkit().getToolNames()).doesNotContain("schedule_task", "stop_task");

            var toolkit = chatAgent.getToolkit();
            var chatTools = call(toolkit, "list_tools", Map.of());
            assertThat(chatTools.getState()).isEqualTo(ToolResultState.SUCCESS);
            assertThat(chatTools.getOutput().toString()).contains("list_tools：", "schedule_task：", "stop_task：");
            var scheduledTools = call(scheduledAgent.getToolkit(), "list_tools", Map.of());
            assertThat(scheduledTools.getState()).isEqualTo(ToolResultState.SUCCESS);
            assertThat(scheduledTools.getOutput().toString()).doesNotContain("schedule_task", "stop_task");

            assertThat(call(toolkit, "invalid cron", "查天气").getState()).isEqualTo(ToolResultState.ERROR);
            assertThat(scheduler.list()).isEmpty();

            var result = call(toolkit, "* * * * * *", "查天气");
            var task = triggered.get(5, TimeUnit.SECONDS);
            assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
            assertThat(result.getOutput().toString()).contains(task.taskId());
            assertThat(task.botId()).isEqualTo(bot.id());
            assertThat(task.zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
            assertThat(task.instruction()).isEqualTo("查天气");
            assertThat(task.chatId()).isEqualTo("chat-1");
            assertThat(scheduler.list()).containsExactly(task);
            assertThat(new ScheduledTaskStore(file).load()).containsExactly(task);
        }
    }

    @Test
    void stopsOnlyTasksForTheCurrentBotAndChatAndPersistsTheChange() {
        Path file = temp.resolve("scheduled-tasks.json");
        String botId = BotRegistry.MAIN.id();
        String cron = "0 0 9 * * *";
        var weather = new SchedTask("weather", botId, cron, "查天气", "chat-1");
        var report = new SchedTask("report", botId, cron, "发简报", "chat-1");
        var otherChat = new SchedTask("other-chat", botId, cron, "查天气", "chat-2");
        var otherBot = new SchedTask("other-bot", "bot-2", cron, "发简报", "chat-1");

        try (var scheduler = new BotTaskScheduler(file)) {
            var toolkit = new Toolkit();
            toolkit.registerTool(new ScheduleTaskTool(scheduler, botId, "chat-1", task -> {}));

            for (var task : new SchedTask[]{weather, report, otherChat, otherBot}) {
                scheduler.register(task, () -> {});
            }

            var ambiguous = call(toolkit, "stop_task", Map.of());
            assertThat(ambiguous.getState()).isEqualTo(ToolResultState.ERROR);
            assertThat(ambiguous.getOutput().toString()).contains(weather.taskId(), report.taskId())
                    .doesNotContain(otherChat.taskId(), otherBot.taskId());
            assertThat(call(toolkit, "stop_task", Map.of("task_id", otherChat.taskId())).getState())
                    .isEqualTo(ToolResultState.ERROR);
            assertThat(call(toolkit, "stop_task", Map.of("task_id", otherBot.taskId())).getState())
                    .isEqualTo(ToolResultState.ERROR);
            assertThat(scheduler.list()).containsExactly(weather, report, otherChat, otherBot);

            assertThat(call(toolkit, "stop_task", Map.of("task_id", report.taskId())).getState())
                    .isEqualTo(ToolResultState.SUCCESS);
            assertThat(call(toolkit, "stop_task", Map.of()).getState()).isEqualTo(ToolResultState.SUCCESS);
            assertThat(call(toolkit, "stop_task", Map.of()).getState()).isEqualTo(ToolResultState.ERROR);
            assertThat(scheduler.list()).containsExactly(otherChat, otherBot);
            assertThat(new ScheduledTaskStore(file).load())
                    .containsExactly(weather.disabled(), report.disabled(), otherChat, otherBot);
        }
    }

    private ToolResultBlock call(Toolkit toolkit, String cron, String instruction) {
        return call(toolkit, "schedule_task", Map.of("cron", cron, "instruction", instruction));
    }

    private ToolResultBlock call(Toolkit toolkit, String name, Map<String, Object> input) {
        var toolUse = ToolUseBlock.builder()
                .name(name)
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
        return toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolUse).input(input).build())
                .block(Duration.ofSeconds(5));
    }
}
