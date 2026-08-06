package com.chinazhouwy.miniharness.finance.research.service;

import com.chinazhouwy.miniharness.finance.research.calculation.LeveragePriceFitCalculator;
import com.chinazhouwy.miniharness.finance.research.data.MarketResearchDataSource;
import com.chinazhouwy.miniharness.finance.research.data.RealMarketResearchDataSource;
import com.chinazhouwy.miniharness.finance.research.domain.EquationFitResult;
import com.chinazhouwy.miniharness.finance.research.domain.MarketResearchDataset;
import com.chinazhouwy.miniharness.finance.research.domain.MarketSeries;
import com.chinazhouwy.miniharness.finance.research.web.MarketResearchView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 真实金融研究数据的短时缓存与计算编排，不把 npx/HTTP 细节泄漏到 Controller。 */
@Service
public final class MarketResearchService {

    private final MarketResearchDataSource dataSource;
    private final LeveragePriceFitCalculator fitCalculator;
    private final Duration cacheTtl;
    private CachedResult cache;

    @Autowired
    public MarketResearchService(
            ObjectMapper objectMapper,
            @Value("${finance.research.lookback-days:420}") int lookbackDays,
            @Value("${finance.research.cache-seconds:900}") long cacheSeconds
    ) {
        this(
                new RealMarketResearchDataSource(objectMapper, java.time.Clock.system(java.time.ZoneId.of("Asia/Shanghai")), lookbackDays),
                new LeveragePriceFitCalculator(),
                Duration.ofSeconds(Math.max(0, cacheSeconds))
        );
    }

    public MarketResearchService(
            MarketResearchDataSource dataSource,
            LeveragePriceFitCalculator fitCalculator,
            Duration cacheTtl
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.fitCalculator = Objects.requireNonNull(fitCalculator, "fitCalculator");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
    }

    public synchronized MarketResearchView getResearch() {
        Instant now = Instant.now();
        if (cache != null && cacheTtl.isZero() == false && now.isBefore(cache.loadedAt().plus(cacheTtl))) {
            return cache.view();
        }
        MarketResearchDataset dataset = dataSource.load();
        MarketResearchView view = toView(dataset);
        cache = new CachedResult(now, view);
        return view;
    }

    private MarketResearchView toView(MarketResearchDataset dataset) {
        List<MarketResearchView.MarketSeriesView> markets = dataset.series().stream()
                .map(this::toMarketView)
                .toList();
        return new MarketResearchView(dataset.retrievedAt(), "REAL_DATA", markets, dataset.sources());
    }

    private MarketResearchView.MarketSeriesView toMarketView(MarketSeries series) {
        EquationFitResult fit = fitCalculator.fit(series);
        return new MarketResearchView.MarketSeriesView(
                series.instrument(),
                series.observations().getFirst().tradeDate(),
                series.observations().getLast().tradeDate(),
                series.observations().size(),
                (int) series.observations().stream().filter(value -> value.leverageImputed()).count(),
                series.observations().getLast().close(),
                series.observations().getLast().leverageValue(),
                fit,
                series.sources()
        );
    }

    private record CachedResult(Instant loadedAt, MarketResearchView view) {
    }
}
