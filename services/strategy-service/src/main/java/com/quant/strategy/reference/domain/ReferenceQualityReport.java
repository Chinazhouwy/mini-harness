package com.quant.strategy.reference.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回测质检报告。
 */
public record ReferenceQualityReport(
    String taskId,
    boolean passed,
    List<ReferenceQualityCheck> checks,
    List<String> suggestions,
    LocalDateTime checkedAt
) {
}
