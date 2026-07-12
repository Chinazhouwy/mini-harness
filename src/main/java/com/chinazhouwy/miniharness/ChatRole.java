package com.chinazhouwy.miniharness;

/**
 * 写入 {@code data/history.json} 时使用的最小角色集合。
 *
 * <p>Spring AI 的 {@code Message} 对象除了文本外还可能携带媒体、工具调用、
 * 元数据等运行时信息。直接把它序列化到 JSON，会把 Spring AI 的内部结构也带进来；
 * 因此当前原型只持久化“谁说的”和“说了什么”，恢复时再转换回 {@code Message}。</p>
 */
public enum ChatRole {
    USER,
    ASSISTANT
}
