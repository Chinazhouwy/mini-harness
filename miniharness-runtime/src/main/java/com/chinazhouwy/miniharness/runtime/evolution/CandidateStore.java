package com.chinazhouwy.miniharness.runtime.evolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Workflow 候选仓储。 */
public class CandidateStore {

    private final Map<String, WorkflowCandidate> candidates = new LinkedHashMap<>();

    public synchronized void save(WorkflowCandidate candidate) {
        candidates.put(candidate.id(), candidate);
    }

    public synchronized Optional<WorkflowCandidate> find(String id) {
        return Optional.ofNullable(candidates.get(id));
    }

    public synchronized List<WorkflowCandidate> all() {
        return List.copyOf(candidates.values());
    }
}
