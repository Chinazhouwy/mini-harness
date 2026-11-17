package com.chinazhouwy.miniharness.runtime.tool;

import java.util.Map;

/** 工具执行失败：携带结构化错误码；与节点失败区分，重试策略由调用方决定。 */
public class ToolFailureException extends RuntimeException {

    private final String errorCode;

    public ToolFailureException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ToolFailureException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
