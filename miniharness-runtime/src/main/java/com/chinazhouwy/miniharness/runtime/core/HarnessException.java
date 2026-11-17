package com.chinazhouwy.miniharness.runtime.core;

/** Harness 通用运行时异常基类，携带结构化错误码供事件与评测消费。 */
public class HarnessException extends RuntimeException {

    private final String errorCode;

    public HarnessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public HarnessException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
