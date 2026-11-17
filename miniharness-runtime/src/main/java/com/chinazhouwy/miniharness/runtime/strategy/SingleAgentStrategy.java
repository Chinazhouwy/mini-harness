package com.chinazhouwy.miniharness.runtime.strategy;

import com.chinazhouwy.miniharness.runtime.workflow.NodeSpec;

/** 策略 A（单 Agent）：每个节点执行前都咨询模型复核——模型参与最大化。 */
public class SingleAgentStrategy extends ModelConsultingStrategy {

    @Override
    public String name() {
        return "single-agent";
    }

    @Override
    protected boolean shouldConsult(NodeSpec spec) {
        return true;
    }

    @Override
    protected String consultPurpose() {
        return "单Agent模式：每个节点执行前咨询模型复核（对照实验 A 组）";
    }
}
