package com.chinazhouwy.miniharness.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

public class FirstAgent {

        public static void main(String[] args) throws IOException {

            Properties props = loadLocalProperties();
            String apiKey = props.getProperty("openrouter.api.key");
            String baseUrl = props.getProperty("openrouter.base.url", "https://openrouter.ai/api/v1");
            String modelName = props.getProperty("openrouter.model", "stealth/crouton-alpha");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "openrouter.api.key 未设置：请在 src/main/resources/openrouter.local.properties 里填写");
            }
            OpenAIChatModel model = OpenAIChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)              // OpenRouter 上的模型全名
                    .baseUrl(baseUrl)          // 自定义 url
                    .stream(true)
                    .build();

            HarnessAgent.Builder agentBuilder =  HarnessAgent.builder()
                     .name("first-name")
                     .agentId("first-agentId")
                     .sysPrompt("you are a helper")
                     .model(model)
                     .workspace(Path.of("./data/workspace"));

            // ===== observe 演示：先让另一个 agent 开口，再把它的回复“旁听”进 agent 的记忆 =====
            // 关键点：observe() 内部固定写往 agent 的默认会话（defaultSessionId，
            // 默认 = agent 名），而 call(msg, runtimeContext) 用的是 runtimeContext 里的
            // sessionId。两边会话不一致时，被观察的消息不会出现在后续 call 的上下文中。
            RuntimeContext runtimeContext = RuntimeContext.builder().userId("alice2").sessionId("demo-session").build();

            try(HarnessAgent agent = agentBuilder
                    .defaultSessionId(runtimeContext.getSessionId()) // 让 observe 与后续 call 落在同一个会话
                    .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state"))) // 避免默认写 ~/.agentscope
                    .build();
                HarnessAgent otherAgent = HarnessAgent.builder()
                    .name("other-agent")
                    .agentId("other-agent")
                    .sysPrompt("你是一个普通的 AI 助手")
                    .model(model)
                    .workspace(Path.of("./data/workspace"))
                    .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                    .build()) {

                // 1) otherAgent 开口说一句话
                Msg otherAgentMsg = otherAgent.call(
                        new UserMessage("自我介绍一下：说你叫李四，28 岁，喜欢画画"),
                        runtimeContext).block();
                System.out.println("[otherAgent] " + otherAgentMsg.getTextContent());

                // 2) 把 otherAgent 的回复旁听进 agent 的记忆——只记忆，不触发 LLM 回复
                //    observe 返回 Mono<Void>，.block() 等它写完即返回（没有返回值）
                agent.observe(otherAgentMsg).block();
                System.out.println("[observe] 已注入记忆，上一步没有产生任何回复");

                // 3) 再问 agent，它应该能复述刚才旁听到的内容
                Msg replay = agent.call(
                        new UserMessage("旁听到的那位 agent 说他叫什么、几岁、喜欢什么？只回答事实"),
                        runtimeContext).block();
                System.out.println("[agent] " + replay.getTextContent());
            }
        }

        /** 读取 src/main/resources/openrouter.local.properties（已写入 .gitignore，不会入库）。 */
        private static Properties loadLocalProperties() throws IOException {
            Properties props = new Properties();
            try (InputStream in = FirstAgent.class.getClassLoader()
                    .getResourceAsStream("openrouter.local.properties")) {
                if (in == null) {
                    throw new IllegalStateException(
                            "找不到 openrouter.local.properties，请确认文件在 src/main/resources/ 下");
                }
                props.load(in);
            }
            return props;
        }

}
