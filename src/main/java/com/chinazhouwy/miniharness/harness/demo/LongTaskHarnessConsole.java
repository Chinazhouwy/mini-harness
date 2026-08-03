package com.chinazhouwy.miniharness.harness.demo;

import com.chinazhouwy.miniharness.harness.domain.*;
import com.chinazhouwy.miniharness.harness.event.*;
import com.chinazhouwy.miniharness.harness.persistence.FileHarnessStateStore;
import com.chinazhouwy.miniharness.harness.runtime.*;

import java.nio.file.Path;

/** 一个只运行本地确定性 Demo 的最小入口。 */
public final class LongTaskHarnessConsole {

    private LongTaskHarnessConsole() {
    }

    public static void main(String[] args) {
        FileHarnessStateStore store = new FileHarnessStateStore(Path.of("data", "harness"));
        HarnessOrchestrator orchestrator = new HarnessOrchestrator(store);
        MissionSnapshot created = orchestrator.createMission(DemoMissionFactory.create());

        System.out.println("Mission id: " + created.mission().id());
        printState(created);

        MissionSnapshot current = created;
        int maxConsoleSteps = 100;
        int steps = 0;
        while (!current.mission().status().isTerminal() && steps < maxConsoleSteps) {
            steps++;
            AdvanceResult result = orchestrator.advanceOneStep(current.mission().id());
            result.events().forEach(LongTaskHarnessConsole::printEvent);
            current = result.snapshot();
            printState(current);
            if (!result.progressed()) {
                break;
            }
        }
        if (!current.mission().status().isTerminal()) {
            System.out.println("[MISSION] paused after max steps=" + maxConsoleSteps);
        }
    }

    private static void printEvent(HarnessEvent event) {
        String type = event.type();
        if (type.equals(HarnessEventType.CHECKPOINT_SAVED.name())) {
            System.out.println("[CHECKPOINT] saved sequence=" + event.sequence());
            return;
        }
        if (event.taskId() != null) {
            System.out.println("[TASK] " + event.taskId() + " " + taskVerb(type));
            return;
        }
        if (type.equals(HarnessEventType.MISSION_CREATED.name())) {
            System.out.println("[MISSION] created");
        } else if (type.equals(HarnessEventType.MISSION_COMPLETED.name())) {
            System.out.println("[MISSION] succeeded");
        } else {
            System.out.println("[MISSION] " + type.toLowerCase());
        }
    }

    private static String taskVerb(String eventType) {
        return switch (eventType) {
            case "TASK_READY" -> "ready";
            case "TASK_STARTED" -> "started";
            case "TASK_SUBMITTED" -> "submitted";
            case "TASK_ACCEPTED" -> "accepted";
            case "TASK_RETRY_SCHEDULED" -> "retry scheduled";
            case "TASK_BLOCKED" -> "blocked";
            case "TASK_FAILED" -> "failed";
            case "TASK_CANCELLED" -> "cancelled";
            default -> eventType.toLowerCase();
        };
    }

    private static void printState(MissionSnapshot snapshot) {
        String currentTask = snapshot.tasks().stream()
                .filter(task -> task.status() == TaskStatus.RUNNING || task.status() == TaskStatus.READY)
                .map(TaskNode::id)
                .findFirst()
                .orElse("-");
        String artifactIds = snapshot.artifacts().stream()
                .map(ArtifactRef::id)
                .reduce((left, right) -> left + ", " + right)
                .orElse("-");
        System.out.printf(
                "[STATE] mission=%s task=%s artifacts=%s sequence=%d%n",
                snapshot.mission().status(),
                currentTask,
                artifactIds,
                snapshot.lastEventSequence()
        );
    }
}
