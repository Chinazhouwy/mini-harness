package com.chinazhouwy.miniharness.session;

import com.chinazhouwy.miniharness.ChatRole;

/**
 * 适合 JSON 持久化的简化消息。
 *
 * <p>不要和 Spring AI 的 {@code Message} 混为一谈：前者是本项目自己的稳定文件格式，
 * 后者是调用模型时使用的运行时对象。{@code MiniHarnessDemo} 在保存时把 Message
 * 转为 StoredMessage，加载时再转回 Message。</p>
 */
public record StoredMessage(
        ChatRole role,
        String content
)  {
}
