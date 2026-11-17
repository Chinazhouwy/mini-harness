package com.chinazhouwy.miniharness.runtime.evolution;

/** 失败标注的结构化缺陷类型（金融研究 Domain Pack 首批）。 */
public final class FailureKinds {

    public static final String UNIT_MISMATCH = "UNIT_MISMATCH";
    public static final String PERIOD_MISMATCH = "PERIOD_MISMATCH";
    public static final String MISSING_FIELD = "MISSING_FIELD";
    public static final String CONTRADICTORY_DATA = "CONTRADICTORY_DATA";
    public static final String UNVERIFIABLE_SOURCE = "UNVERIFIABLE_SOURCE";
    public static final String WRONG_RESULT = "WRONG_RESULT";
    public static final String OTHER = "OTHER";

    /** 数据口径类缺陷——触发「插入口径验证节点」改进提案的集合。 */
    public static final java.util.Set<String> DATA_QUALITY_KINDS = java.util.Set.of(
            UNIT_MISMATCH, PERIOD_MISMATCH, MISSING_FIELD, CONTRADICTORY_DATA);

    private FailureKinds() {
    }
}
