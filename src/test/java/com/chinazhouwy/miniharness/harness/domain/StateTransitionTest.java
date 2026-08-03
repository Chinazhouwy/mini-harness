package com.chinazhouwy.miniharness.harness.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateTransitionTest {

    @Test
    void validMissionAndTaskTransitionsAreExplicit() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Mission mission = new Mission("mission-1", "demo", List.of("done"), MissionStatus.CREATED, 0, now, now);
        assertEquals(MissionStatus.READY, mission.transitionTo(MissionStatus.READY, now).status());

        TaskNode task = new TaskNode("task-1", "demo task", List.of(), TaskStatus.PENDING, 2, "", "");
        TaskNode ready = task.transitionTo(TaskStatus.READY);
        TaskNode running = ready.transitionTo(TaskStatus.RUNNING);
        TaskNode submitted = running.transitionTo(TaskStatus.SUBMITTED);
        assertEquals(TaskStatus.ACCEPTED, submitted.transitionTo(TaskStatus.ACCEPTED).status());
    }

    @Test
    void acceptedTaskCannotReturnToRunning() {
        TaskNode accepted = new TaskNode(
                "task-1",
                "demo task",
                List.of(),
                TaskStatus.ACCEPTED,
                1,
                "",
                "done"
        );

        StateTransitionException exception = assertThrows(
                StateTransitionException.class,
                () -> accepted.transitionTo(TaskStatus.RUNNING)
        );

        assertEquals("Task cannot transition from ACCEPTED to RUNNING", exception.getMessage());
    }

    @Test
    void cancelledMissionCannotContinue() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Mission cancelled = new Mission(
                "mission-1",
                "demo",
                List.of(),
                MissionStatus.CANCELLED,
                2,
                now,
                now
        );

        assertThrows(
                StateTransitionException.class,
                () -> cancelled.transitionTo(MissionStatus.RUNNING)
        );
        assertDoesNotThrow(() -> StateTransitions.requireTaskTransition(TaskStatus.RUNNING, TaskStatus.RETRYABLE));
    }
}
