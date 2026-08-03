package com.chinazhouwy.miniharness.harness;

import java.util.List;

public interface HarnessStateStore {

    MissionSnapshot load(String missionId);

    void appendEvent(HarnessEvent event);

    void saveCheckpoint(Checkpoint checkpoint);

    List<HarnessEvent> readEvents(String missionId);

    default boolean exists(String missionId) {
        return false;
    }
}
