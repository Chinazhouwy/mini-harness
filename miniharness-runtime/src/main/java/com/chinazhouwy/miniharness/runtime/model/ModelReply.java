package com.chinazhouwy.miniharness.runtime.model;

/** 模型回复与用量。token 数仅为成本统计参考，不参与结果正确性判断。 */
public record ModelReply(String content, long promptTokens, long completionTokens, long latencyMs) {
}
