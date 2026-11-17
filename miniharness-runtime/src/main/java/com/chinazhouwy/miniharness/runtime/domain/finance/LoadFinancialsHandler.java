package com.chinazhouwy.miniharness.runtime.domain.finance;

import com.chinazhouwy.miniharness.runtime.tool.ToolFailureException;
import com.chinazhouwy.miniharness.runtime.workflow.NodeHandler;
import com.chinazhouwy.miniharness.runtime.workflow.NodeInput;
import com.chinazhouwy.miniharness.runtime.workflow.NodeOutput;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点处理器：读取财报。统一经 ToolInvoker 调用（事件/预算/超时由 Harness 层承担）。
 * 输出保留全部口径信息（来源/报告期/单位），供验证与报告节点使用。
 */
public class LoadFinancialsHandler implements NodeHandler {

    public static final String TYPE = "finance-load-financials";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NodeOutput execute(NodeInput input) {
        String caseId = String.valueOf(input.workflowInput().getOrDefault("caseId", ""));
        String ticker = String.valueOf(input.workflowInput().getOrDefault("ticker", ""));
        boolean defaulted = caseId == null || caseId.isBlank() || "null".equals(caseId);
        if (defaulted) {
            // 演示默认案例：真实部署应按公司映射到具体数据集。
            caseId = "case-clean";
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("caseId", caseId);
        if (ticker != null && !ticker.isBlank() && !"null".equals(ticker)) {
            args.put("ticker", ticker);
        }
        try {
            Map<String, Object> loaded = input.context().toolInvoker().invoke(
                    FinanceTools.LOAD_STATEMENTS, args,
                    input.context().taskId(), input.context().runId(),
                    input.context().cancelled().getAsBoolean(),
                    input.context().budget(), 5_000, 1);
            loaded.put("caseIdDefaulted", defaulted);
            return NodeOutput.success(loaded);
        } catch (ToolFailureException e) {
            return NodeOutput.failed(e.errorCode(), e.getMessage());
        }
    }
}
