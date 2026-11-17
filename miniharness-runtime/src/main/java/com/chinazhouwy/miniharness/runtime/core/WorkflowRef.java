package com.chinazhouwy.miniharness.runtime.core;

import java.util.Objects;

/**
 * Workflow 身份 = 名称 + 整数版本号。
 * 版本一经发布不可变；同一个 name 的新版本号必须严格递增，避免新旧流程混淆。
 */
public record WorkflowRef(String name, int version) {

    public WorkflowRef {
        Objects.requireNonNull(name, "workflow name 不能为空");
        if (name.isBlank()) {
            throw new IllegalArgumentException("workflow name 不能为空白");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("workflow 版本必须为正整数: " + version);
        }
    }

    @Override
    public String toString() {
        return name + "@v" + version;
    }
}
