package com.chinazhouwy.miniharness.runtime.evolution;

import java.util.ArrayList;
import java.util.List;

/** 内存经验存储：插入序稳定。 */
public class InMemoryExperienceStore implements ExperienceStore {

    private final List<ExperienceRecord> records = new ArrayList<>();

    @Override
    public synchronized void append(ExperienceRecord record) {
        records.add(record);
    }

    @Override
    public synchronized List<ExperienceRecord> all() {
        return List.copyOf(records);
    }

    @Override
    public synchronized void annotate(String runId, ExperienceRecord.FailureAnnotation annotation) {
        for (int i = 0; i < records.size(); i++) {
            ExperienceRecord record = records.get(i);
            if (record.runId() != null && record.runId().value().equals(runId)) {
                List<ExperienceRecord.FailureAnnotation> merged = new ArrayList<>(record.annotations());
                merged.add(annotation);
                records.set(i, new ExperienceRecord(record.runId(), record.taskId(), record.workflow(),
                        record.triggeredBy(), record.outcome(), record.errorCode(), record.errorSummary(),
                        record.nodes(), record.output(), merged, record.createdAt()));
                return;
            }
        }
    }
}
