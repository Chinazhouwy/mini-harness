package com.chinazhouwy.miniharness.harness.domain;

/** 非法状态转换是编程错误，不能静默修正。 */
public class StateTransitionException extends IllegalStateException {

    public StateTransitionException(String message) {
        super(message);
    }
}
