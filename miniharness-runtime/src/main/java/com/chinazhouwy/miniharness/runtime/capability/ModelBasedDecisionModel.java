package com.chinazhouwy.miniharness.runtime.capability;

import com.chinazhouwy.miniharness.runtime.model.ModelPort;
import com.chinazhouwy.miniharness.runtime.model.ModelRequest;

import java.util.List;
import java.util.Optional;

/**
 * 基于模型端口的轻量决策实现（可选）。提示词要求模型只输出候选编号，
 * 解析失败返回 empty，绝不猜答案。仅在部署了真实模型适配器时才会被装配。
 */
public class ModelBasedDecisionModel implements DecisionModel {

    private final ModelPort modelPort;

    public ModelBasedDecisionModel(ModelPort modelPort) {
        this.modelPort = modelPort;
    }

    @Override
    public Optional<SemanticJudgment> judge(SemanticQuery query) {
        if (query.candidates().isEmpty()) {
            return Optional.empty();
        }
        StringBuilder prompt = new StringBuilder("任务: ").append(query.purpose()).append('\n')
                .append("输入: ").append(query.input()).append('\n')
                .append("候选:\n");
        for (int i = 0; i < query.candidates().size(); i++) {
            prompt.append(i + 1).append(". ").append(query.candidates().get(i)).append('\n');
        }
        prompt.append("只回复最匹配候选的编号数字，无法判断回复 0。");
        String reply;
        try {
            reply = modelPort.complete(new ModelRequest("你是路由判断器，只输出数字。", prompt.toString(), java.util.Map.of())).content();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        String digits = reply == null ? "" : reply.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return Optional.empty();
        }
        int index = Integer.parseInt(digits.substring(0, 1));
        if (index <= 0 || index > query.candidates().size()) {
            return Optional.empty();
        }
        return Optional.of(new SemanticJudgment(query.candidates().get(index - 1), 0.5,
                "模型选择（置信度未校准，仅供参考）"));
    }
}
