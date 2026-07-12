package com.chinazhouwy.miniharness;

/**
 * 一道主问题或追问的一轮交互草图。
 *
 * <p>和 {@link QuestionAttempt} 相比，它额外区分本轮是主问题还是追问；后续如果
 * {@link InterviewQuestion} 真正接入主流程，就可以用多个 {@code QuestionRound}
 * 表达一条追问链。</p>
 */
public record QuestionRound(QuestionType type,
                            String question,
                            String answer,
                            AnswerEvaluation evaluation) {
}
