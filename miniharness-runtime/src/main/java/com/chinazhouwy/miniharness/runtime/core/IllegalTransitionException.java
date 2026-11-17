package com.chinazhouwy.miniharness.runtime.core;

/** 非法状态转换：Task/Run/Node 的生命周期被绕过时抛出。 */
public class IllegalTransitionException extends HarnessException {
    public IllegalTransitionException(String message) {
        super("ILLEGAL_TRANSITION", message);
    }
}
