package com.chinazhouwy.miniharness.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * 为真实的 ToolCallingManager 增加执行日志，具体工具解析和执行仍交给 delegate。
 * 参数和结果可能包含敏感数据，完整输出仅适合受控的本地学习环境。
 */
public class TraceToolCallingManager implements ToolCallingManager {

    private static final Logger log = LoggerFactory.getLogger(TraceToolCallingManager.class);

    private final ToolCallingManager delegate;

    public TraceToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }


    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        // 当前装饰器配置在 Advisor 中；OpenAiChatModel 的定义解析使用其自身的
        // manager，因此当前模型路径不一定调用这里，不能用该日志判断是否注册成功。
        List<ToolDefinition> definitions = delegate.resolveToolDefinitions(chatOptions);
        log.info("[工具定义] 数量={}", definitions.size());
        return definitions;
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        log.info("[工具执行] 开始");
        chatResponse.getResults().forEach(generation -> generation.getOutput().getToolCalls()
                .forEach(call -> log.info("[工具请求] id={} name={} arguments={}",
                        call.id(), call.name(), call.arguments())));

        try {
            ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);
            log.info("[工具执行] 结束 returnDirect={}", result.returnDirect());
            // 默认 manager 将本批次工具结果放在历史的最后一条 ToolResponseMessage 中。
            var history = result.conversationHistory();
            if (!history.isEmpty() && history.get(history.size() - 1) instanceof ToolResponseMessage message) {
                message.getResponses().forEach(response -> log.info("[工具结果] id={} name={} result={}",
                        response.id(), response.name(), response.responseData()));
            }
            return result;
        } catch (RuntimeException exception) {
            log.warn("[工具执行] 异常类型={}", exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
