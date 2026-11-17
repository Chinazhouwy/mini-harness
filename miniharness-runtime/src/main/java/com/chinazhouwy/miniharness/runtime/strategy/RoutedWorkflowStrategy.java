package com.chinazhouwy.miniharness.runtime.strategy;

import com.chinazhouwy.miniharness.runtime.workflow.NodeSpec;

/**
 * 策略 B（规则路由 + Agent）：确定性规则优先，仅当节点声明 needsModel=true 时才咨询模型。
 * 未声明 needsModel 的确定性节点（本运行时的全部注册处理器默认如此）0 次模型调用。
 */
public class RoutedWorkflowStrategy extends ModelConsultingStrategy {

    @Override
    public String name() {
        return "routed-workflow";
    }

    @Override
    protected boolean shouldConsult(NodeSpec spec) {
        return Boolean.parseBoolean(String.valueOf(spec.params().getOrDefault("needsModel", "false")));
    }

    @Override
    protected String consultPurpose() {
        return "规则路由模式：仅 needsModel=true 的节点咨询模型（对照实验 B 组）";
    }
}
