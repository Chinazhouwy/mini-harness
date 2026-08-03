package com.chinazhouwy.miniharness.harness.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MissionDefinitionTest {

    @Test
    void acyclicGraphIsAccepted() {
        assertDoesNotThrow(() -> new MissionDefinition(
                "valid",
                "demo",
                List.of("done"),
                List.of(
                        task("a", List.of()),
                        task("b", List.of("a")),
                        task("c", List.of("a", "b"))
                )
        ));
    }

    @Test
    void cyclicGraphIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MissionDefinition(
                "cycle",
                "demo",
                List.of("done"),
                List.of(
                        task("a", List.of("b")),
                        task("b", List.of("a"))
                )
        ));
    }

    @Test
    void selfDependencyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MissionDefinition(
                "self-cycle",
                "demo",
                List.of("done"),
                List.of(task("a", List.of("a")))
        ));
    }

    private static TaskNode task(String id, List<String> dependencies) {
        return new TaskNode(id, "task " + id, dependencies, TaskStatus.PENDING, 2, "", "");
    }
}
