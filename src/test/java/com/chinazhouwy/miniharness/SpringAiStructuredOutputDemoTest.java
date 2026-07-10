package com.chinazhouwy.miniharness;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("llm")
class SpringAiStructuredOutputDemoTest extends SpringAiLlmDemoSupport {

    private static final Logger log = LoggerFactory.getLogger(SpringAiStructuredOutputDemoTest.class);

    // 结构化输出 Demo 用的 Java Record。
    //
    // 这里故意先不做复杂业务对象，只写几个和面试评测相关的字段。
    // Spring AI 会根据这个类型，引导模型输出可以映射成该结构的数据。
    record CoveredPoint(String knowledgePoint, String answerQuote, String explanation) {
    }

    record MissingPoint(String knowledgePoint, String explanation, boolean critical) {
    }

    record EvaluationSketch(
            double score,
            List<CoveredPoint> coveredPoints,
            List<MissingPoint> missingPoints,
            String followUpQuestion) {
    }

    @Test
    void mapModelOutputToJavaRecord() {
        requireLlmConfig();

        // 示例：结构化输出。
        //
        // 普通 content() 返回的是一段自然语言文本，例如：
        // “这段回答整体不错，但遗漏了……”
        //
        // entity(EvaluationSketch.class) 会让 Spring AI 尝试把模型输出转换成 Java 对象。
        // 这样你就可以直接拿到：
        // - evaluation.score()
        // - evaluation.coveredPoints()
        // - evaluation.missingPoints()
        // - evaluation.followUpQuestion()
        //
        // 这对 MiniHarness 很关键，因为后面要做 Eval、追问策略、能力证据，
        // 都不能只靠一段散文式评价。
        //
        // 但要注意：结构化输出不是“绝对可靠”。
        // 模型可能：
        // - 分数超出 0~10
        // - 编造知识点
        // - answerQuote 不是候选人原话
        // - missingPoints 为空但实际有遗漏
        //
        // 所以后续业务代码一定要加校验。这个 Demo 先只学习 API 怎么用。
        EvaluationSketch evaluation = chatClient()
                .prompt()
                .system("""
                        你是一名严格的 Java 面试评测器。
                        只根据候选人的原回答判断，不要补充候选人没有说过的内容。
                        """)
                .user("""
                        问题：线程池提交一个任务后的完整执行流程是什么？

                        候选人回答：
                        任务提交后，线程池会先创建核心线程执行任务。
                        核心线程满了以后，任务会进入阻塞队列。
                        队列满了以后继续创建线程，达到最大线程数以后执行拒绝策略。

                        请给出结构化评测草稿。
                        """)
                .call()
                .entity(EvaluationSketch.class);

        log.info("Structured evaluation: {}", evaluation);
    }
}
