package com.chinazhouwy.miniharness.runtime.model;

import com.chinazhouwy.miniharness.runtime.core.HarnessException;

/** 无模型部署模式：任何模型调用都会显式失败，避免离线环境被误认为有模型结论。 */
public class NoopModelPort implements ModelPort {

    public static final String ERROR_CODE = "MODEL_UNAVAILABLE";

    @Override
    public ModelReply complete(ModelRequest request) {
        throw new HarnessException(ERROR_CODE,
                "当前为无模型运行模式，模型调用被拒绝；请改用确定性路径或配置真实模型适配器");
    }
}
