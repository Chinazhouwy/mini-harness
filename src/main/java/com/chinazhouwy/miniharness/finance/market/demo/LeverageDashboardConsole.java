package com.chinazhouwy.miniharness.finance.market.demo;

import com.chinazhouwy.miniharness.finance.market.data.FixtureMarketLeverageDataSource;
import com.chinazhouwy.miniharness.finance.market.execution.LeverageDashboardTaskExecutor;
import com.chinazhouwy.miniharness.finance.market.persistence.LeverageArtifactStore;
import com.chinazhouwy.miniharness.finance.market.verification.LeverageDashboardTaskVerifier;
import com.chinazhouwy.miniharness.finance.market.workflow.LeverageDashboardMissionFactory;
import com.chinazhouwy.miniharness.harness.domain.MissionSnapshot;
import com.chinazhouwy.miniharness.harness.persistence.FileHarnessStateStore;
import com.chinazhouwy.miniharness.harness.runtime.HarnessOrchestrator;
import com.chinazhouwy.miniharness.harness.runtime.RunResult;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 不访问网络的金融领域闭环入口。 */
public final class LeverageDashboardConsole {

    private static final DateTimeFormatter MISSION_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private LeverageDashboardConsole() {
    }

    public static void main(String[] args) {
        String missionId = args.length == 0
                ? "leverage-" + LocalDateTime.now().format(MISSION_TIME)
                : args[0];
        FileHarnessStateStore stateStore = new FileHarnessStateStore(Path.of("data", "harness-finance"));
        LeverageArtifactStore artifactStore = new LeverageArtifactStore(Path.of("data", "finance-artifacts"));
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                stateStore,
                new LeverageDashboardTaskExecutor(new FixtureMarketLeverageDataSource(), artifactStore),
                new LeverageDashboardTaskVerifier()
        );

        MissionSnapshot created = orchestrator.createMission(LeverageDashboardMissionFactory.create(missionId));
        RunResult result = orchestrator.runUntilPause(created.mission().id(), 20);

        System.out.println("Mission: " + result.snapshot().mission().id());
        System.out.println("Status: " + result.snapshot().mission().status());
        System.out.println("Stop reason: " + result.stopReason());
        result.snapshot().artifacts().stream()
                .filter(artifact -> artifact.type().equals("text/markdown"))
                .findFirst()
                .ifPresent(artifact -> System.out.println("Report: " + artifact.path()));
    }
}
