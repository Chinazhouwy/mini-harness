package com.chinazhouwy.miniharness.feishu;

import com.chinazhouwy.miniharness.model.ModelConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/** 使用 miniharness-core 的 ModelConfig 创建 AgentScope4j 模型与 Agent 工厂。 */
@Configuration(proxyBeanMethods = false)
public class AiConfig {

    @Bean
    public OpenAIChatModel openAIChatModel() throws IOException {
        ModelConfig config = ModelConfig.loadProperties();
        return OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.model())
                .nativeStructuredOutput(false)
                .stream(true)
                .build();
    }

    @Bean
    public AgentFactory agentFactory(OpenAIChatModel model,
                                     FeishuTools tools,
                                     DeepSeekWebTools webTools,
                                     @Value("${feishu.state.dir:logs/feishu-agent-state}") String stateDir) {
        return new AgentFactory(model, stateDir, tools, webTools);
    }
}
