package com.chinazhouwy.miniharness.harness.persistence;

import com.chinazhouwy.miniharness.harness.domain.Checkpoint;
import com.chinazhouwy.miniharness.harness.domain.MissionSnapshot;
import com.chinazhouwy.miniharness.harness.event.HarnessEvent;

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
