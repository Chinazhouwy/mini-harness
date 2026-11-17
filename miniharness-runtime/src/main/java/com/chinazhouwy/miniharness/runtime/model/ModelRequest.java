package com.chinazhouwy.miniharness.runtime.model;

import java.util.Map;

/** 一次模型补全请求；runtime 不感知任何框架类型，模型实现方自行适配。 */
public record ModelRequest(String system, String user, Map<String, Object> options) {

    public ModelRequest {
        if (options == null) {
            options = Map.of();
        }
    }
}
