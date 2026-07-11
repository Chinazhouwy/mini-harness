package com.chinazhouwy.miniharness;

public record QuestionAttempt(String question,
                              String answer,
                              AnswerEvaluation evaluation) {
}
