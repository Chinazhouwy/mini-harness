package com.chinazhouwy.miniharness.finance.market.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** 金融数据的来源和时间口径，避免指标脱离证据单独存在。 */
public record SourceEvidence(
        String id,
        String name,
        String uri,
        LocalDate dataDate,
        Instant retrievedAt,
        String field,
        String unit,
        String note
) {

    public SourceEvidence {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Evidence id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Evidence name must not be blank");
        }
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Evidence uri must not be blank");
        }
        dataDate = Objects.requireNonNull(dataDate, "dataDate");
        retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
        field = Objects.requireNonNullElse(field, "");
        unit = Objects.requireNonNullElse(unit, "");
        note = Objects.requireNonNullElse(note, "");
    }
}
