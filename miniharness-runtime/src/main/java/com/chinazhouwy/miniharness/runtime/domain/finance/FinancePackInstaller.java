package com.chinazhouwy.miniharness.runtime.domain.finance;

import com.chinazhouwy.miniharness.runtime.HarnessEngine;

/** 金融 Domain Pack 安装器：注册工具与节点处理器并发布 Workflow v1（幂等）。 */
public final class FinancePackInstaller {

    private FinancePackInstaller() {
    }

    public static synchronized void install(HarnessEngine engine, String operator) {
        engine.registerTool(FinanceTools.loadStatementsTool());
        engine.registerNodeHandler(new LoadFinancialsHandler());
        engine.registerNodeHandler(new DataQualityValidationHandler());
        engine.registerNodeHandler(new ComputeMarginsHandler());
        engine.registerNodeHandler(new GenerateReportHandler());
        if (engine.workflows().find(FinancePack.WORKFLOW_NAME, 1).isEmpty()) {
            engine.workflows().install(FinancePack.workflowV1(1));
            engine.workflows().publish(FinancePack.WORKFLOW_NAME, 1, operator, null);
        }
    }
}
