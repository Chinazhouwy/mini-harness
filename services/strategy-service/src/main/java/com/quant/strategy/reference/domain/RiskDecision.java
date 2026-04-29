package com.quant.strategy.reference.domain;

/**
 * 风控结果。
 *
 * <p>这里先用简单的 allow/reason 结构。真实系统可以继续扩展为风险码、
 * 建议减仓比例、触发规则列表等。</p>
 */
public record RiskDecision(
    boolean allowed,
    String reason
) {

    public static RiskDecision allow(String reason) {
        return new RiskDecision(true, reason);
    }

    public static RiskDecision reject(String reason) {
        return new RiskDecision(false, reason);
    }
}
