package com.chinazhouwy.miniharness;

/**
 * 一次“回答评测”的最小结果。
 *
 * <p>它是当前控制台原型让模型通过 {@code entity(AnswerEvaluation.class)}
 * 映射出来的结构，不是最终的评测领域模型。现在只有一个分数和一个遗漏点，
 * 目的是先观察：模型能不能把自然语言评价转换为程序可读取的数据。</p>
 *
 * <p>后续如果要做可靠评测，通常会把 {@code missingPoint} 扩展为多个带证据的
 * 覆盖点、遗漏点和错误点；在这个阶段先保持简单，避免还没验证效果就造出很大的结构。</p>
 *
 * @param score        模型给出的 0 到 10 分建议值；当前没有在 Java 侧校验范围
 * @param correct      模型判断回答是否“基本正确”；它只是模型意见，不是客观事实
 * @param comment      给用户展示的简短评价
 * @param missingPoint 模型认为最值得追问的一个遗漏点；没有时约定为空字符串
 */
public record AnswerEvaluation(
        int score,
        boolean correct,
        String comment,
        String missingPoint
) {
}
