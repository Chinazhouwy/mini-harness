package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.HarnessException;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.event.EventBus;
import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.event.HarnessEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workflow 版本账本：唯一有权改变「发布指针」的组件。
 * 定义一经登记不可变；发布/回滚只移动指针并留下审计记录，旧版本永远可查、可回滚。
 */
public class WorkflowRegistry {

    /** 发布审计记录：谁在何时基于哪个候选发布了哪个版本。 */
    public record Publication(String workflowName, int version, String candidateId,
                              String publishedBy, Instant at, boolean rollback) {
    }

    private final EventBus events;
    private final Map<String, Map<Integer, WorkflowDefinition>> definitions = new LinkedHashMap<>();
    private final Map<String, Integer> published = new LinkedHashMap<>();
    private final Map<String, List<Publication>> history = new LinkedHashMap<>();

    public WorkflowRegistry(EventBus events) {
        this.events = events;
    }

    /** 登记一个新版本（不改变发布指针）。同版本重复登记报错。 */
    public synchronized WorkflowDefinition install(WorkflowDefinition definition) {
        WorkflowRef ref = definition.ref();
        Map<Integer, WorkflowDefinition> byVersion =
                definitions.computeIfAbsent(ref.name(), k -> new LinkedHashMap<>());
        if (byVersion.containsKey(ref.version())) {
            throw new HarnessException("WORKFLOW_VERSION_CONFLICT",
                    "版本已存在: " + ref + "，演化必须产生新版本");
        }
        byVersion.put(ref.version(), definition);
        return definition;
    }

    /** 直接发布某个已登记版本（种子初始化与测试装配用；候选发布请走 ApprovalService）。 */
    public synchronized WorkflowDefinition publish(String name, int version, String by, String candidateId) {
        WorkflowDefinition definition = require(name, version);
        published.put(name, version);
        recordHistory(new Publication(name, version, candidateId, by, Instant.now(), false));
        events.publish(HarnessEvent.builder(EventType.WORKFLOW_PUBLISHED)
                .workflow(definition.ref())
                .message("Workflow " + name + " v" + version + " 已发布，发布人 " + by)
                .payload(Map.of("version", version, "publishedBy", by,
                        "candidateId", candidateId == null ? "" : candidateId)));
        return definition;
    }

    /** 回滚到某个已存在且曾发布过的版本。 */
    public synchronized WorkflowDefinition rollback(String name, int targetVersion, String by) {
        if (!wasPublished(name, targetVersion)) {
            throw new HarnessException("ROLLBACK_TARGET_INVALID",
                    "回滚目标版本从未发布过: " + name + " v" + targetVersion);
        }
        WorkflowDefinition definition = require(name, targetVersion);
        published.put(name, targetVersion);
        recordHistory(new Publication(name, targetVersion, null, by, Instant.now(), true));
        events.publish(HarnessEvent.builder(EventType.WORKFLOW_ROLLED_BACK)
                .workflow(definition.ref())
                .message("Workflow " + name + " 回滚到 v" + targetVersion + "，操作人 " + by)
                .payload(Map.of("targetVersion", targetVersion, "publishedBy", by)));
        return definition;
    }

    public synchronized Optional<WorkflowDefinition> published(String name) {
        Integer version = published.get(name);
        return version == null ? Optional.empty() : Optional.ofNullable(require(name, version));
    }

    public synchronized WorkflowDefinition publishedRequired(String name) {
        return published(name).orElseThrow(() -> new HarnessException("WORKFLOW_NOT_PUBLISHED",
                "Workflow 尚无已发布版本: " + name));
    }

    public synchronized Optional<WorkflowDefinition> find(String name, int version) {
        Map<Integer, WorkflowDefinition> byVersion = definitions.get(name);
        return byVersion == null ? Optional.empty() : Optional.ofNullable(byVersion.get(version));
    }

    public synchronized List<WorkflowDefinition> versions(String name) {
        Map<Integer, WorkflowDefinition> byVersion = definitions.get(name);
        return byVersion == null ? List.of() : List.copyOf(byVersion.values());
    }

    public synchronized Optional<Integer> publishedVersion(String name) {
        return Optional.ofNullable(published.get(name));
    }

    public synchronized int nextVersion(String name) {
        Map<Integer, WorkflowDefinition> byVersion = definitions.get(name);
        int max = 0;
        if (byVersion != null) {
            for (Integer v : byVersion.keySet()) {
                max = Math.max(max, v);
            }
        }
        return max + 1;
    }

    public synchronized List<Publication> publicationHistory(String name) {
        return List.copyOf(history.getOrDefault(name, List.of()));
    }

    /** 已知 Workflow 名称集合（任意版本已登记即算已知）。 */
    public synchronized List<String> knownWorkflowNames() {
        return List.copyOf(definitions.keySet());
    }

    private WorkflowDefinition require(String name, int version) {
        WorkflowDefinition definition = definitions.getOrDefault(name, Map.of()).get(version);
        if (definition == null) {
            throw new HarnessException("WORKFLOW_NOT_FOUND", "Workflow 定义不存在: " + name + " v" + version);
        }
        return definition;
    }

    private void recordHistory(Publication publication) {
        history.computeIfAbsent(publication.workflowName(), k -> new ArrayList<>()).add(publication);
    }

    private boolean wasPublished(String name, int version) {
        for (Publication p : history.getOrDefault(name, List.of())) {
            if (p.version() == version) {
                return true;
            }
        }
        return false;
    }
}
