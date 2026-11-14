package com.chinazhouwy.miniharness;

import com.chinazhouwy.miniharness.advisor.TraceStreamAdvisor;
import com.chinazhouwy.miniharness.advisor.TraceToolCallingManager;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
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
                .httpClientBuilderCustomizer(
                        builder -> builder.interceptor(new ModelHttpTraceInterceptor())
                )
                .build();
    }

    public Flux<String> run(String system, String user, List<Object> tools, boolean stream){

        // Advisor 协调整个工具循环；装饰器只观察 Manager 接手的工具执行批次。
        ToolCallingManager defaultManager = ToolCallingManager.builder().build();
        ToolCallingManager traceManager = new TraceToolCallingManager(defaultManager);
        ToolCallingAdvisor toolAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(traceManager)
                .build();

        // TraceStreamAdvisor 排在工具循环之后，因此能够观察循环内的流式模型调用。
        ChatClient client = ChatClient.builder(model).defaultAdvisors(toolAdvisor,
                new TraceStreamAdvisor()).build();

        ChatClient.ChatClientRequestSpec answer = client.prompt()
                .system(system)
                .user(user);
        if (tools != null && !tools.isEmpty()) {
            answer.tools(tools);
        }
        if(stream){
            return answer.stream().content();
        }else{
            // call() 在此处同步执行，Flux.just 只包装结果，并未使调用变为异步。
            return Flux.just(answer.call().content());
        }
    }

}
