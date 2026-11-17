package com.chinazhouwy.miniharness.runtime.core;

import java.util.UUID;

/** 交互会话 ID：Chat 页面与任务创建来源的关联身份。 */
public record SessionId(String value) {
    public static SessionId random() {
        return new SessionId("sess-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public String toString() {
        return value;
    }
}
