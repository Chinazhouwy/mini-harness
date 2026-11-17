package com.chinazhouwy.miniharness.runtime.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Skill：可复用能力说明与触发条件。与 Workflow 的区别——
 * Skill 是「怎么做的知识」，Workflow 是「固化的确定性流程」；
 * Skill 被编译进 Workflow 发布后自动降级（COMPILED_INTO_WORKFLOW），避免重复匹配。
 */
public class Skill {

    public enum SkillStatus { ACTIVE, COMPILED_INTO_WORKFLOW, DEPRECATED }

    private final String id;
    private final String name;
    private final String triggerIntent;
    private final String triggerPattern;
    private final String instruction;
    private final List<String> sourceRunIds;
    private final Instant createdAt;
    private SkillStatus status;
    private String note = "";

    public Skill(String id, String name, String triggerIntent, String triggerPattern,
                 String instruction, List<String> sourceRunIds, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.triggerIntent = triggerIntent;
        this.triggerPattern = triggerPattern;
        this.instruction = instruction;
        this.sourceRunIds = List.copyOf(sourceRunIds);
        this.createdAt = createdAt;
        this.status = SkillStatus.ACTIVE;
    }

    public synchronized void changeStatus(SkillStatus target, String note) {
        this.status = target;
        this.note = note == null ? "" : note;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String triggerIntent() {
        return triggerIntent;
    }

    public String triggerPattern() {
        return triggerPattern;
    }

    public String instruction() {
        return instruction;
    }

    public List<String> sourceRunIds() {
        return sourceRunIds;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized SkillStatus status() {
        return status;
    }

    public synchronized String note() {
        return note;
    }
}
