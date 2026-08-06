package com.chinazhouwy.miniharness.finance.research.domain;

import java.util.List;
import java.util.Objects;

/** 一个候选方程的训练、验证结果和可绘制路径。 */
public record EquationFitResult(
        String modelName,
        String equation,
        int sampleCount,
        int trainCount,
        double trainRmsePct,
        double validationRmsePct,
        double validationR2,
        double validationCorrelation,
        List<EquationFitPoint> points,
        List<String> notes
) {

    public EquationFitResult {
        modelName = requireText(modelName, "modelName");
        equation = requireText(equation, "equation");
        if (sampleCount < 1 || trainCount < 1 || trainCount > sampleCount) {
            throw new IllegalArgumentException("Invalid fit sample counts");
        }
        requireNonNegative("trainRmsePct", trainRmsePct);
        requireNonNegative("validationRmsePct", validationRmsePct);
        requireFinite("validationR2", validationR2);
        requireFinite("validationCorrelation", validationCorrelation);
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Fit result must contain points");
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNullElse(value, "").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative and finite");
        }
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
