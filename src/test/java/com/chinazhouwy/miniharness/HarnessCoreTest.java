//package com.chinazhouwy.miniharness;
//
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.mockito.ArgumentCaptor;
//import org.springframework.ai.chat.messages.AssistantMessage;
//import org.springframework.ai.chat.model.ChatResponse;
//import org.springframework.ai.chat.model.Generation;
//import org.springframework.ai.chat.prompt.Prompt;
//import org.springframework.ai.openai.OpenAiChatModel;
//import org.springframework.ai.openai.OpenAiChatOptions;
//import org.springframework.test.util.ReflectionTestUtils;
//import reactor.core.publisher.Flux;
//
//import java.util.List;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.mock;
//import static org.mockito.Mockito.verify;
//import static org.mockito.Mockito.when;
//
///** 使用固定模型响应测试客户端封装，不启动 Spring，不调用外部模型服务。 */
//class HarnessCoreTest {
//
//    private OpenAiChatModel model;
//    private HarnessCore harness;
//
//    @BeforeEach
//    void setUp() {
//        model = mock(OpenAiChatModel.class);
//        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
//
//        harness = new HarnessCore();
//        // 手动 new 不会执行 @PostConstruct；暂用反射注入，保留当前生产代码。
//        // 后续改成构造注入时，这里即可直接 new HarnessCore(model)。
//        ReflectionTestUtils.setField(harness, "model", model);
//    }
//
//    @Test
//    void nonStreamingCallPassesMessagesAndReturnsAnswer() {
//        when(model.call(any(Prompt.class))).thenReturn(response("任务尚未完成"));
//
//        List<String> answers = harness.run("你是任务助手", "任务完成了吗？", null, false)
//                .collectList().block();
//
//        assertEquals(List.of("任务尚未完成"), answers);
//        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
//        verify(model).call(prompt.capture());
//        assertEquals("你是任务助手", prompt.getValue().getSystemMessage().getText());
//        assertEquals("任务完成了吗？", prompt.getValue().getUserMessage().getText());
//    }
//
//    @Test
//    void streamingCallReturnsEachTextChunk() {
//        when(model.stream(any(Prompt.class)))
//                .thenReturn(Flux.just(response("任务"), response("尚未完成")));
//
//        List<String> chunks = harness.run("你是任务助手", "任务完成了吗？", List.of(), true)
//                .collectList().block();
//
//        assertEquals(List.of("任务", "尚未完成"), chunks);
//        verify(model).stream(any(Prompt.class));
//    }
//
//    private static ChatResponse response(String text) {
//        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
//    }
//}
