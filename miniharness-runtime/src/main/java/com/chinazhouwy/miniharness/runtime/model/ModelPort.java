package com.chinazhouwy.miniharness.runtime.model;

/**
 * 框架无关的模型适配端口。MiniHarness 内核只依赖此接口：
 * - 生产实现由 miniharness-spring-ai 适配层提供（Spring AI OpenAiChatModel）。
 * - 离线测试使用 ScriptedModelPort（脚本化固定回复）。
 * - 无模型部署使用 NoopModelPort（任何调用都会显式失败，绝不静默假装成功）。
 */
public interface ModelPort {

    ModelReply complete(ModelRequest request);
}
