package com.chinazhouwy.miniharness.runtime.core;

import java.util.UUID;

/** 执行 ID：一次具体执行的稳定身份。 */
public record RunId(String value) {
    public static RunId random() {
        return new RunId("run-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public String toString() {
        return value;
    }
}
