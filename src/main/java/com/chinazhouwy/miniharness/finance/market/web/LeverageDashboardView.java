package com.chinazhouwy.miniharness.finance.market.web;

import com.chinazhouwy.miniharness.finance.market.domain.LeverageMetricSnapshot;
import com.chinazhouwy.miniharness.finance.market.domain.LeverageScoreSnapshot;
import com.chinazhouwy.miniharness.finance.market.domain.SourceEvidence;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 前端只读展示模型，保持和金融计算层解耦。 */
public record LeverageDashboardView(
        String market,
        LocalDate from,
        LocalDate to,
        LeverageMetricSnapshot latest,
        LeverageScoreSnapshot score,
        List<LeverageHistoryPoint> history,
        List<SourceEvidence> sources
) {

    public LeverageDashboardView {
        market = Objects.requireNonNull(market, "market");
        from = Objects.requireNonNull(from, "from");
        to = Objects.requireNonNull(to, "to");
        latest = Objects.requireNonNull(latest, "latest");
        score = Objects.requireNonNull(score, "score");
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }
}
