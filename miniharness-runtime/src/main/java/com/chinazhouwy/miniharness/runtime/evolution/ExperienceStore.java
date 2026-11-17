package com.chinazhouwy.miniharness.runtime.evolution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 经验存储接口（内存实现）。 */
public interface ExperienceStore {

    void append(ExperienceRecord record);

    List<ExperienceRecord> all();

    default List<ExperienceRecord> byWorkflow(String workflowName) {
        List<ExperienceRecord> matched = new ArrayList<>();
        for (ExperienceRecord record : all()) {
            if (record.workflow() != null && record.workflow().name().equals(workflowName)) {
                matched.add(record);
            }
        }
        return matched;
    }

    default Optional<ExperienceRecord> byRun(String runId) {
        for (ExperienceRecord record : all()) {
            if (record.runId() != null && record.runId().value().equals(runId)) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    /** 向已有经验追加失败标注（例如用户质疑）。记录不存在时静默忽略。 */
    default void annotate(String runId, ExperienceRecord.FailureAnnotation annotation) {
        // 默认实现：不支持原地标注的存储可覆写。
    }
}
