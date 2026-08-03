package com.chinazhouwy.miniharness.harness;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 集中维护 Mission 和 Task 的允许迁移，避免各调用方自行修改状态。 */
public final class StateTransitions {

    private static final Map<MissionStatus, Set<MissionStatus>> MISSION_TRANSITIONS = Map.of(
            MissionStatus.CREATED, EnumSet.of(MissionStatus.READY, MissionStatus.CANCELLED),
            MissionStatus.READY, EnumSet.of(MissionStatus.RUNNING, MissionStatus.CANCELLED),
            MissionStatus.RUNNING, EnumSet.of(
                    MissionStatus.WAITING,
                    MissionStatus.VERIFYING,
                    MissionStatus.BLOCKED,
                    MissionStatus.FAILED,
                    MissionStatus.CANCELLED
            ),
            MissionStatus.WAITING, EnumSet.of(
                    MissionStatus.RUNNING,
                    MissionStatus.BLOCKED,
                    MissionStatus.FAILED,
                    MissionStatus.CANCELLED
            ),
            MissionStatus.VERIFYING, EnumSet.of(
                    MissionStatus.RUNNING,
                    MissionStatus.SUCCEEDED,
                    MissionStatus.BLOCKED,
                    MissionStatus.FAILED,
                    MissionStatus.CANCELLED
            ),
            MissionStatus.SUCCEEDED, EnumSet.noneOf(MissionStatus.class),
            MissionStatus.FAILED, EnumSet.noneOf(MissionStatus.class),
            MissionStatus.BLOCKED, EnumSet.noneOf(MissionStatus.class),
            MissionStatus.CANCELLED, EnumSet.noneOf(MissionStatus.class)
    );

    private static final Map<TaskStatus, Set<TaskStatus>> TASK_TRANSITIONS = Map.of(
            TaskStatus.PENDING, EnumSet.of(TaskStatus.READY, TaskStatus.CANCELLED),
            TaskStatus.READY, EnumSet.of(TaskStatus.RUNNING, TaskStatus.CANCELLED),
            TaskStatus.RUNNING, EnumSet.of(
                    TaskStatus.SUBMITTED,
                    TaskStatus.RETRYABLE,
                    TaskStatus.BLOCKED,
                    TaskStatus.FAILED,
                    TaskStatus.CANCELLED
            ),
            TaskStatus.SUBMITTED, EnumSet.of(
                    TaskStatus.ACCEPTED,
                    TaskStatus.RETRYABLE,
                    TaskStatus.BLOCKED,
                    TaskStatus.FAILED,
                    TaskStatus.CANCELLED
            ),
            TaskStatus.ACCEPTED, EnumSet.noneOf(TaskStatus.class),
            TaskStatus.RETRYABLE, EnumSet.of(TaskStatus.READY, TaskStatus.BLOCKED, TaskStatus.CANCELLED),
            TaskStatus.BLOCKED, EnumSet.noneOf(TaskStatus.class),
            TaskStatus.FAILED, EnumSet.noneOf(TaskStatus.class),
            TaskStatus.CANCELLED, EnumSet.noneOf(TaskStatus.class)
    );

    private static final Map<TaskAttemptStatus, Set<TaskAttemptStatus>> ATTEMPT_TRANSITIONS = Map.of(
            TaskAttemptStatus.RUNNING, EnumSet.of(
                    TaskAttemptStatus.SUBMITTED,
                    TaskAttemptStatus.RETRYABLE,
                    TaskAttemptStatus.BLOCKED,
                    TaskAttemptStatus.FAILED
            ),
            TaskAttemptStatus.SUBMITTED, EnumSet.of(
                    TaskAttemptStatus.SUCCEEDED,
                    TaskAttemptStatus.RETRYABLE,
                    TaskAttemptStatus.BLOCKED,
                    TaskAttemptStatus.FAILED
            ),
            TaskAttemptStatus.SUCCEEDED, EnumSet.noneOf(TaskAttemptStatus.class),
            TaskAttemptStatus.RETRYABLE, EnumSet.noneOf(TaskAttemptStatus.class),
            TaskAttemptStatus.BLOCKED, EnumSet.noneOf(TaskAttemptStatus.class),
            TaskAttemptStatus.FAILED, EnumSet.noneOf(TaskAttemptStatus.class)
    );

    private StateTransitions() {
    }

    public static void requireMissionTransition(MissionStatus from, MissionStatus to) {
        requireTransition("Mission", from, to, MISSION_TRANSITIONS.get(from));
    }

    public static void requireTaskTransition(TaskStatus from, TaskStatus to) {
        requireTransition("Task", from, to, TASK_TRANSITIONS.get(from));
    }

    public static void requireAttemptTransition(TaskAttemptStatus from, TaskAttemptStatus to) {
        requireTransition("TaskAttempt", from, to, ATTEMPT_TRANSITIONS.get(from));
    }

    private static <T extends Enum<T>> void requireTransition(
            String subject,
            T from,
            T to,
            Set<T> allowed
    ) {
        if (from == to || allowed == null || !allowed.contains(to)) {
            throw new StateTransitionException(
                    "%s cannot transition from %s to %s".formatted(subject, from, to)
            );
        }
    }
}
