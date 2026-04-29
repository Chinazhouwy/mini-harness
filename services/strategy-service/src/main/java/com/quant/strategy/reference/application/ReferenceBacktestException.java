package com.quant.strategy.reference.application;

/**
 * Reference slice 使用的业务异常。
 *
 * <p>Controller 会把它转换为 400 响应。这样服务层可以清晰表达“请求不合法”
 * 或“数据不足”，而不是把所有问题都包装成系统错误。</p>
 */
public class ReferenceBacktestException extends RuntimeException {

    public ReferenceBacktestException(String message) {
        super(message);
    }
}
