package com.chinazhouwy.miniharness;

import jakarta.annotation.PostConstruct;
import org.springaicommunity.agent.tools.GlobTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;


@Component
public class HarnessCore {

    private OpenAiChatModel model;

    @PostConstruct
    void initModel() throws IOException {
        ModelConfig config = ModelConfig.loadProperties();
        model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(config.apiKey())
                        .baseUrl(config.baseUrl())
                        .model(config.model())
                        .build())
                .build();
    }

    public Flux<String> run(String system, String user, List<Object> tools, boolean stream){
        ChatClient client = ChatClient.create(model);
        ChatClient.ChatClientRequestSpec answer = client.prompt()
                .system(system)
                .user(user);
        if (tools != null && !tools.isEmpty()) {
            answer.tools(tools);
        }
        if(stream){
            return answer.stream().content();
        }else{
            return Flux.just(answer.call().content());
        }
    }

}
