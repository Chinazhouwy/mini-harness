package com.chinazhouwy.miniharness.finance.market.workflow;

import com.chinazhouwy.miniharness.finance.market.execution.LeverageDashboardTaskExecutor;
import com.chinazhouwy.miniharness.harness.domain.MissionDefinition;
import com.chinazhouwy.miniharness.harness.domain.TaskNode;
import com.chinazhouwy.miniharness.harness.domain.TaskStatus;

import java.util.List;

/** 市场杠杆温度计的第一个长程任务图。 */
public final class LeverageDashboardMissionFactory {

    private LeverageDashboardMissionFactory() {
    }

    public static MissionDefinition create(String missionId) {
        return new MissionDefinition(
                missionId,
                "生成公开可观测 A 股杠杆温度计报告",
                List.of(
                        "原始数据带来源和交易日期",
                        "指标和评分可以从 Artifact 重算",
                        "报告包含状态、评分、证据和风险限制"
                ),
                List.of(
                        task(LeverageDashboardTaskExecutor.COLLECT_MARKET_DATA, "采集市场杠杆数据", List.of()),
                        task(LeverageDashboardTaskExecutor.CALCULATE_METRICS, "计算杠杆指标", List.of(LeverageDashboardTaskExecutor.COLLECT_MARKET_DATA)),
                        task(LeverageDashboardTaskExecutor.SCORE_MARKET_STATE, "计算市场状态评分", List.of(LeverageDashboardTaskExecutor.CALCULATE_METRICS)),
                        task(LeverageDashboardTaskExecutor.WRITE_REPORT, "生成 Markdown 报告", List.of(LeverageDashboardTaskExecutor.SCORE_MARKET_STATE, LeverageDashboardTaskExecutor.COLLECT_MARKET_DATA, LeverageDashboardTaskExecutor.CALCULATE_METRICS))
                )
        );
    }

    private static TaskNode task(String id, String title, List<String> dependencies) {
        return new TaskNode(id, title, dependencies, TaskStatus.PENDING, 2, "等待前置 Artifact", "");
    }
}
