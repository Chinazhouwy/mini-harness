package com.chinazhouwy.miniharness.finance.market.workflow;

import com.chinazhouwy.miniharness.finance.market.data.FixtureMarketLeverageDataSource;
import com.chinazhouwy.miniharness.finance.market.execution.LeverageDashboardTaskExecutor;
import com.chinazhouwy.miniharness.finance.market.persistence.LeverageArtifactStore;
import com.chinazhouwy.miniharness.finance.market.verification.LeverageDashboardTaskVerifier;
import com.chinazhouwy.miniharness.harness.domain.MissionStatus;
import com.chinazhouwy.miniharness.harness.event.HarnessEventType;
import com.chinazhouwy.miniharness.harness.persistence.FileHarnessStateStore;
import com.chinazhouwy.miniharness.harness.runtime.HarnessOrchestrator;
import com.chinazhouwy.miniharness.harness.runtime.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeverageDashboardWorkflowTest {

    @TempDir
    Path tempDir;

    @Test
    void leverageDashboardRunsThroughHarnessAndWritesVerifiedReport() throws Exception {
        String missionId = "leverage-workflow-test";
        FileHarnessStateStore stateStore = new FileHarnessStateStore(tempDir.resolve("harness"));
        LeverageArtifactStore artifactStore = new LeverageArtifactStore(tempDir.resolve("artifacts"));
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(
                stateStore,
                new LeverageDashboardTaskExecutor(new FixtureMarketLeverageDataSource(), artifactStore),
                new LeverageDashboardTaskVerifier()
        );

        orchestrator.createMission(LeverageDashboardMissionFactory.create(missionId));
        RunResult result = orchestrator.runUntilPause(missionId, 20);

        assertEquals(MissionStatus.SUCCEEDED, result.snapshot().mission().status());
        assertEquals("COMPLETED", result.stopReason().name());
        var report = result.snapshot().artifacts().stream()
                .filter(artifact -> artifact.type().equals("text/markdown"))
                .findFirst()
                .orElseThrow();
        assertTrue(Files.isRegularFile(Path.of(report.path())));
        String markdown = Files.readString(Path.of(report.path()), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("POTENTIAL_CLEARANCE"));
        assertTrue(markdown.contains("## 风险与限制"));
        assertFalse(report.sha256().isBlank());
        assertTrue(stateStore.readEvents(missionId).stream()
                .anyMatch(event -> event.type().equals(HarnessEventType.MISSION_COMPLETED.name())));
    }
}
