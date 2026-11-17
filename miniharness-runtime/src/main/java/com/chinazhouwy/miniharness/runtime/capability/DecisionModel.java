package com.chinazhouwy.miniharness.runtime.capability;

import java.util.List;
import java.util.Optional;

/**
 * 可插拔轻量决策模型接口：用于规则之间的语义模糊判断（例如质疑消息关联哪个任务）。
 * 不绑定 JEV 或任何具体 LLM；实现可以是轻量 LLM、分类器或其他服务。
 * 任何实现都必须认识到：模型输出的置信度未经过校准，只能作为参考，
 * Harness 对路由结果的最终决定权不交给该接口。
 */
public interface DecisionModel {

    /**
     * 对候选做语义判断。返回 Optional.empty() 表示该模型无法判断（路由器回退到规则确认路径）。
     */
    Optional<SemanticJudgment> judge(SemanticQuery query);

    /** 语义判断请求。 */
    record SemanticQuery(String purpose, String input, List<String> candidates) {
    }

    /** 语义判断结果：label 必须来自 candidates 或为 null。 */
    record SemanticJudgment(String label, double confidence, String explanation) {
    }
}
