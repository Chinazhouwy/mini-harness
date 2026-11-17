package com.chinazhouwy.miniharness.runtime.domain.finance;

import com.chinazhouwy.miniharness.runtime.tool.ToolContext;
import com.chinazhouwy.miniharness.runtime.tool.ToolFailureException;
import com.chinazhouwy.miniharness.runtime.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合成财报读取工具：金融研究 Workflow 的数据入口。
 * 确定性工具——相同参数永远返回相同数据；数字不经模型转手。
 */
public final class FinanceTools {

    public static final String LOAD_STATEMENTS = "finance.load_statements";

    private FinanceTools() {
    }

    public static ToolSpec loadStatementsTool() {
        return new ToolSpec(LOAD_STATEMENTS,
                "读取合成财报数据集（来源/报告期/单位齐全；非真实行情）",
                Map.of("caseId", "可选，受控案例 ID（如 case-unit-mismatch）",
                        "ticker", "公司代码（合成代码，如 SYN-HXZZ）"),
                FinanceTools::executeLoad);
    }

    private static Map<String, Object> executeLoad(Map<String, Object> args, ToolContext context) {
        String caseId = text(args.get("caseId"));
        String ticker = text(args.get("ticker"));
        if (caseId == null || caseId.isBlank()) {
            throw new ToolFailureException("ARGUMENT_MISSING", "缺少 caseId 参数，无法定位数据集");
        }
        FinanceDataset.FinanceCase datasetCase = FinanceDataset.loadCase(caseId);
        if (ticker != null && !ticker.isBlank() && !ticker.equals(datasetCase.ticker())) {
            throw new ToolFailureException("TICKER_MISMATCH",
                    "案例 " + caseId + " 属于 " + datasetCase.ticker() + "，与请求的 " + ticker + " 不符");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", datasetCase.caseId());
        result.put("company", datasetCase.company());
        result.put("ticker", datasetCase.ticker());
        result.put("injectedDefect", datasetCase.injectedDefect() == null ? "" : datasetCase.injectedDefect());
        result.put("disclaimer", "合成数据集，仅用于 Harness 能力验证");
        var statements = new java.util.ArrayList<Map<String, Object>>();
        for (FinanceDataset.StatementRow row : datasetCase.statements()) {
            Map<String, Object> rowMap = new LinkedHashMap<>();
            rowMap.put("period", row.period());
            rowMap.put("unit", row.unit());
            rowMap.put("source", row.source());
            rowMap.put("sourceDate", row.sourceDate());
            rowMap.put("revenue", row.revenue());
            rowMap.put("netProfit", row.netProfit());
            statements.add(rowMap);
        }
        result.put("statements", statements);
        return result;
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }
}
