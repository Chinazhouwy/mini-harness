package com.chinazhouwy.miniharness.model;

import com.chinazhouwy.miniharness.interceptor.ModelHttpTraceInterceptor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;

public class ModelCreator {

    private OpenAiChatModel model;

    public OpenAiChatModel getModel() throws IOException {
        ModelConfig config = ModelConfig.loadProperties();
        model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(config.apiKey())
                        .baseUrl(config.baseUrl())
                        .model(config.model())
                        .build())
                .httpClientBuilderCustomizer(
                        builder -> builder.interceptor(new ModelHttpTraceInterceptor())
                )
                .build();
        return model;
    }

}
