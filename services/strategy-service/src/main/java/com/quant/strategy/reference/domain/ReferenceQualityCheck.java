package com.quant.strategy.reference.domain;

import java.math.BigDecimal;

/**
 * 单项质检结果。
 */
public record ReferenceQualityCheck(
    String name,
    boolean passed,
    BigDecimal actual,
    BigDecimal threshold,
    String message
) {
}
