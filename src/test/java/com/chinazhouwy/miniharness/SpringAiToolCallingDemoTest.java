package com.chinazhouwy.miniharness;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@Tag("llm")
class SpringAiToolCallingDemoTest extends SpringAiLlmDemoSupport {

    private static final Logger log = LoggerFactory.getLogger(SpringAiToolCallingDemoTest.class);

    @Test
    void frameworkControlledToolCallingWithChatClient() {
        requireToolDemoEnabled();

        // 示例 1：ChatClient 自动工具调用。
        //
        // 工具调用可以先这样理解：
        // 模型本身不能直接读你的数据库、文件或业务代码。
        // 你把 Java 方法声明成工具后，模型可以“请求调用这个工具”。
        // 真正执行工具的是 Java，不是模型。
        //
        // .tools(new QuestionTools()) 做了两件事：
        // 1. 把 QuestionTools 里带 @Tool 的方法描述给模型
        // 2. 当模型要求调用工具时，由 Spring AI 自动执行 Java 方法
        //
        // 它“暴露工具”，但不等于“强迫模型使用工具”。模型仍可能直接回答。
        // Prompt 写“使用工具”能提高模型调用的概率；如果业务上真的要求必须调用，
        // 应该选择下面的手动模式，在 Java 中检查 response.hasToolCalls()，没有调用就重试或报错。
        //
        // 这个模式最省心：
        // model 请求工具 -> Spring AI 执行工具 -> 工具结果发回模型 -> 模型生成最终回答
        //
        // 缺点是循环过程被框架接管。以后 MiniHarness 如果要控制权限、事件、状态保存，
        // 就更需要看下面那个“手动工具循环”的例子。
        String response = chatClient()
                .prompt()
                .user("""
                        使用工具读取 code=thread-pool-execution 的题目资料。
                        然后用两句话说明这道题最容易漏掉的点。
                        """)
                .tools(new QuestionTools())
                .call()
                .content();

        log.info("Framework-controlled tool response: {}", response);
    }

    @Test
    void userControlledToolCallingLoopWithChatModel() {
        requireToolDemoEnabled();

        // 示例 2：手动控制工具调用循环。
        //
        // 这个例子比上一个复杂，但更接近你以后要学的 MiniHarness。
        //
        // 自动模式中，Spring AI 帮你跑完整个工具循环。
        // 手动模式中，你自己控制：
        // - 第一次把工具定义发给模型
        // - 检查模型有没有请求 tool call
        // - 执行工具
        // - 把工具结果放回对话历史
        // - 再次调用模型
        // - 限制最多循环几次
        //
        // 这就是 Agent Runtime 的雏形。
        // 现在不用急着抽象，只要先看懂这个循环。
        ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

        // ToolCallingChatOptions 里放工具定义。
        // ToolCallbacks.from(new QuestionTools()) 会扫描 QuestionTools 中的 @Tool 方法，
        // 生成模型可以理解的工具描述。
        ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(new QuestionTools()))
                .build();

        // Prompt = 消息 + 本次调用选项。
        // 这里的选项里包含 toolCallbacks，所以模型会知道有哪些工具可用。
        Prompt prompt = new Prompt("""
                使用工具读取 code=thread-pool-execution 的题目资料。
                然后给出一个面试追问。
                """, chatOptions);

        // 第一次调用模型。
        // 如果模型觉得需要工具，它返回的 ChatResponse 里会包含 toolCalls。
        // 注意：即使 Prompt 写了“使用工具”，模型也可能不调；这就是为什么“是否必须调用”
        // 不能只交给 Prompt，而要由 Java 根据 hasToolCalls() 做业务判断。
        ChatResponse response = chatModel.call(prompt);

        int iteration = 0;
        int maxIterations = 3;

        // 手动循环的核心：
        // 只要模型还在请求工具，并且没超过最大次数，就继续执行工具。
        //
        // 这里的 maxIterations 很重要。
        // 如果没有限制，模型可能一直请求工具，形成死循环。
        while (response.hasToolCalls() && iteration < maxIterations) {
            iteration++;
            log.info("Manual tool loop iteration {}", iteration);

            // 执行模型请求的工具。
            // executeToolCalls 会根据 ChatResponse 中的 tool call，找到对应 Java 工具并运行。
            ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(prompt, response);

            // 工具执行结果会变成新的 conversationHistory。
            // 再把这段历史发给模型，模型就能基于工具结果生成下一步回答。
            prompt = new Prompt(toolResult.conversationHistory(), chatOptions);
            response = chatModel.call(prompt);
        }

        log.info("Manual tool loop final response: {}", response.getResult().getOutput().getText());
    }

    static class QuestionTools {

        // @Tool 会把这个 Java 方法暴露给模型。
        //
        // description 很重要：模型不是读你的方法实现来理解工具，
        // 它主要看工具名、参数名和 description。
        //
        // @ToolParam 用来描述参数含义。
        // 模型会根据用户问题，决定给 code 传什么值。
        @Tool(description = "Load a small interview question rubric by code.")
        String loadQuestionRubric(@ToolParam(description = "Question code") String code) {
            // 这里故意不用数据库，只返回一段写死的题目资料。
            // Demo 先学工具调用机制，不要一上来引入持久化。
            if (!"thread-pool-execution".equals(code)) {
                return "Unknown question code: " + code;
            }
            return """
                    question: 线程池提交一个任务后的完整执行流程是什么？
                    mustCover:
                    - workerCount < corePoolSize 时优先创建核心线程
                    - 达到 corePoolSize 后任务进入 workQueue
                    - workQueue 满后才尝试创建非核心线程直到 maximumPoolSize
                    - 仍无法处理时执行 RejectedExecutionHandler
                    commonMistake:
                    - 以为核心线程满后会直接创建非核心线程，忽略队列优先级
                    """;
        }
    }
}
