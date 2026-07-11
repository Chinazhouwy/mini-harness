package com.chinazhouwy.miniharness;

public record AnswerEvaluation(
        int score,
        boolean correct,
        String comment,
        String missingPoint
) {
}
