package com.chinazhouwy.miniharness;

/**
 * 一次“题目 - 候选人回答 - 模型评测”的最小快照。
 *
 * <p>它会写进 {@code data/attempts.json}，用于之后回看模型当时如何评分。它还不是
 * 可解释的能力证据：没有 Rubric 版本、没有答案引用，也没有追问后的纠正记录。</p>
 */
public record QuestionAttempt(String question,
                              String answer,
                              AnswerEvaluation evaluation) {
}
