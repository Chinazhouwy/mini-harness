package com.chinazhouwy.miniharness.finance.market.execution;

import com.chinazhouwy.miniharness.finance.market.calculation.LeverageMetricCalculator;
import com.chinazhouwy.miniharness.finance.market.calculation.LeverageScoreCalculator;
import com.chinazhouwy.miniharness.finance.market.data.MarketLeverageDataSource;
import com.chinazhouwy.miniharness.finance.market.domain.LeverageMetricSeries;
import com.chinazhouwy.miniharness.finance.market.domain.LeverageScoreSnapshot;
import com.chinazhouwy.miniharness.finance.market.domain.MarketLeverageDataset;
import com.chinazhouwy.miniharness.finance.market.domain.SourceEvidence;
import com.chinazhouwy.miniharness.finance.market.persistence.LeverageArtifactStore;
import com.chinazhouwy.miniharness.finance.market.report.LeverageDashboardReportRenderer;
import com.chinazhouwy.miniharness.harness.domain.ArtifactRef;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutionContext;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutionResult;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 把市场杠杆温度计拆成 Harness 可恢复的四个任务。 */
public final class LeverageDashboardTaskExecutor implements TaskExecutor {

    public static final String COLLECT_MARKET_DATA = "collect-market-data";
    public static final String CALCULATE_METRICS = "calculate-metrics";
    public static final String SCORE_MARKET_STATE = "score-market-state";
    public static final String WRITE_REPORT = "write-report";

    private static final String MARKET_DATA_ARTIFACT = "market-data.json";
    private static final String METRICS_ARTIFACT = "metrics.json";
    private static final String SCORE_ARTIFACT = "score.json";
    private static final String REPORT_ARTIFACT = "leverage-dashboard.md";

    private final MarketLeverageDataSource dataSource;
    private final LeverageArtifactStore artifactStore;
    private final LeverageMetricCalculator metricCalculator;
    private final LeverageScoreCalculator scoreCalculator;
    private final LeverageDashboardReportRenderer reportRenderer;

    public LeverageDashboardTaskExecutor(
            MarketLeverageDataSource dataSource,
            LeverageArtifactStore artifactStore
    ) {
        this(dataSource, artifactStore, new LeverageMetricCalculator(), new LeverageScoreCalculator());
    }

    public LeverageDashboardTaskExecutor(
            MarketLeverageDataSource dataSource,
            LeverageArtifactStore artifactStore,
            LeverageMetricCalculator metricCalculator,
            LeverageScoreCalculator scoreCalculator
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.metricCalculator = Objects.requireNonNull(metricCalculator, "metricCalculator");
        this.scoreCalculator = Objects.requireNonNull(scoreCalculator, "scoreCalculator");
        this.reportRenderer = new LeverageDashboardReportRenderer();
    }

    @Override
    public TaskExecutionResult execute(TaskExecutionContext context) {
        return switch (context.task().id()) {
            case COLLECT_MARKET_DATA -> collectMarketData(context);
            case CALCULATE_METRICS -> calculateMetrics(context);
            case SCORE_MARKET_STATE -> scoreMarketState(context);
            case WRITE_REPORT -> writeReport(context);
            default -> TaskExecutionResult.failure(
                    "未知的金融任务",
                    "Unsupported leverage dashboard task: " + context.task().id(),
                    List.of("task=" + context.task().id())
            );
        };
    }

    private TaskExecutionResult collectMarketData(TaskExecutionContext context) {
        MarketLeverageDataset dataset = dataSource.load();
        ArtifactRef artifact = artifactStore.writeJson(
                context.mission().id(),
                MARKET_DATA_ARTIFACT,
                dataset,
                "finance.market.dataset",
                "带来源和日期口径的市场杠杆原始数据"
        );
        return TaskExecutionResult.success(
                "已采集 " + dataset.observations().size() + " 个交易日的市场杠杆数据",
                List.of(
                        "market=" + dataset.market(),
                        "observationCount=" + dataset.observations().size(),
                        "sourceCount=" + dataset.sources().size()
                ),
                List.of(artifact)
        );
    }

    private TaskExecutionResult calculateMetrics(TaskExecutionContext context) {
        MarketLeverageDataset dataset = artifactStore.readJson(
                requireArtifact(context, COLLECT_MARKET_DATA, MARKET_DATA_ARTIFACT),
                MarketLeverageDataset.class
        );
        LeverageMetricSeries metrics = new LeverageMetricSeries(metricCalculator.calculate(dataset));
        ArtifactRef artifact = artifactStore.writeJson(
                context.mission().id(),
                METRICS_ARTIFACT,
                metrics,
                "finance.market.metrics",
                "杠杆比例、买入强度、增长率、出清和波动率指标"
        );
        return TaskExecutionResult.success(
                "已计算 " + metrics.snapshots().size() + " 个有效指标快照",
                List.of("firstDate=" + metrics.snapshots().getFirst().tradeDate(),
                        "lastDate=" + metrics.snapshots().getLast().tradeDate()),
                List.of(artifact)
        );
    }

    private TaskExecutionResult scoreMarketState(TaskExecutionContext context) {
        LeverageMetricSeries metrics = artifactStore.readJson(
                requireArtifact(context, CALCULATE_METRICS, METRICS_ARTIFACT),
                LeverageMetricSeries.class
        );
        LeverageScoreSnapshot score = scoreCalculator.calculate(metrics.snapshots());
        ArtifactRef artifact = artifactStore.writeJson(
                context.mission().id(),
                SCORE_ARTIFACT,
                score,
                "finance.market.score",
                "可解释的杠杆过热、出清和止跌确认评分"
        );
        return TaskExecutionResult.success(
                "已生成市场状态评分：" + score.regime(),
                score.evidence(),
                List.of(artifact)
        );
    }

    private TaskExecutionResult writeReport(TaskExecutionContext context) {
        MarketLeverageDataset dataset = artifactStore.readJson(
                requireArtifact(context, COLLECT_MARKET_DATA, MARKET_DATA_ARTIFACT),
                MarketLeverageDataset.class
        );
        LeverageMetricSeries metrics = artifactStore.readJson(
                requireArtifact(context, CALCULATE_METRICS, METRICS_ARTIFACT),
                LeverageMetricSeries.class
        );
        LeverageScoreSnapshot score = artifactStore.readJson(
                requireArtifact(context, SCORE_MARKET_STATE, SCORE_ARTIFACT),
                LeverageScoreSnapshot.class
        );
        String markdown = reportRenderer.render(dataset, metrics.snapshots(), score);
        ArtifactRef artifact = artifactStore.writeMarkdown(
                context.mission().id(),
                REPORT_ARTIFACT,
                markdown,
                "公开可观测市场杠杆温度计 Markdown 报告"
        );
        return TaskExecutionResult.success(
                "已生成市场杠杆温度计报告",
                List.of("regime=" + score.regime(), "reportPath=" + artifact.path()),
                List.of(artifact)
        );
    }

    private Path requireArtifact(TaskExecutionContext context, String taskId, String artifactName) {
        String artifactId = context.mission().id() + "-" + artifactName.replace('.', '-');
        return context.artifacts().stream()
                .filter(artifact -> artifact.id().equals(artifactId))
                .findFirst()
                .map(artifact -> Path.of(artifact.path()))
                .orElseThrow(() -> new IllegalStateException(
                        "Missing artifact for " + taskId + ": " + artifactId
                ));
    }
}
