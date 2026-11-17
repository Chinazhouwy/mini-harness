package com.chinazhouwy.miniharness.runtime.domain.finance;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.workflow.NodeHandler;
import com.chinazhouwy.miniharness.runtime.workflow.NodeInput;
import com.chinazhouwy.miniharness.runtime.workflow.NodeOutput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据口径验证节点（Workflow v2 新增）：在计算前检查
 * 单位一致 / 报告期口径一致 / 必填字段 / 多来源一致性 / 来源可核实性。
 * 这是「把原本可能交给 LLM 的重复判断转化为确定性 Java 逻辑」的关键节点：
 * 判定规则固定、可单测、零模型成本。
 */
public class DataQualityValidationHandler implements NodeHandler {

    public static final String TYPE = "finance-validate-quality";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeOutput execute(NodeInput input) {
        // 节点参数（NodeSpec.params）允许定制上游数据节点 ID 与校验强度。
        String loadNode = String.valueOf(input.params().getOrDefault("loadNode", "load-financials"));
        NodeOutput loaded = input.upstreamOutputs().get(new NodeId(loadNode));
        if (loaded == null || loaded.data().isEmpty()) {
            return NodeOutput.failed("VALIDATION_INPUT_MISSING", "未找到上游数据节点 " + loadNode + " 的输出");
        }
        List<Map<String, Object>> statements = (List<Map<String, Object>>) loaded.data().get("statements");
        if (statements == null || statements.isEmpty()) {
            return NodeOutput.failed("MISSING_FIELD", "数据集无报表行");
        }
        boolean aggressive = Boolean.parseBoolean(String.valueOf(
                input.params().getOrDefault("aggressive", "false")));

        Map<String, String> checkResults = new LinkedHashMap<>();
        // 1. 来源可核实性：来源名称与发布日期必须存在。
        for (Map<String, Object> row : statements) {
            String source = String.valueOf(row.getOrDefault("source", ""));
            String sourceDate = String.valueOf(row.getOrDefault("sourceDate", ""));
            if (source.isBlank() || "null".equals(source) || !source.startsWith("synthetic-")
                    || sourceDate == null || sourceDate.isBlank() || "null".equals(sourceDate)) {
                return NodeOutput.failed("UNVERIFIABLE_SOURCE",
                        "报表行 " + row.get("period") + " 来源不可核实: source=" + source + ", sourceDate=" + sourceDate);
            }
        }
        checkResults.put("source-verifiable", "ok");

        // 2. 单位一致。
        String unit = null;
        for (Map<String, Object> row : statements) {
            String rowUnit = String.valueOf(row.getOrDefault("unit", ""));
            if (unit == null) {
                unit = rowUnit;
            } else if (!unit.equals(rowUnit)) {
                return NodeOutput.failed("UNIT_MISMATCH",
                        "报表行单位不一致: " + unit + " vs " + rowUnit + "（period=" + row.get("period") + "）");
            }
        }
        checkResults.put("unit-consistency", "ok(" + unit + ")");

        // 3. 报告期口径一致：全部为年报或全部为同一季度口径。
        String spanType = null;
        for (Map<String, Object> row : statements) {
            String period = String.valueOf(row.getOrDefault("period", ""));
            String current = period.endsWith("-FY") ? "FY" : period.contains("-Q") ? "QUARTER:" + period : "OTHER";
            if (spanType == null) {
                spanType = current;
            } else if (!spanType.equals(current)) {
                return NodeOutput.failed("PERIOD_MISMATCH",
                        "报告期口径不一致: " + spanType + " vs " + current);
            }
        }
        checkResults.put("period-consistency", "ok(" + spanType + ")");

        // 4. 必填字段存在且为正数。
        for (Map<String, Object> row : statements) {
            Double revenue = number(row.get("revenue"));
            Double netProfit = number(row.get("netProfit"));
            if (revenue == null || netProfit == null || revenue <= 0 || netProfit <= 0) {
                return NodeOutput.failed("MISSING_FIELD",
                        "报表行 " + row.get("period") + " 缺失或非法字段: revenue=" + revenue + ", netProfit=" + netProfit);
            }
            if (aggressive) {
                // 过激模式：连可选字段也强制要求（用于演示「改进候选也可能失败」）。
                if (row.get("totalAssets") == null || row.get("grossProfit") == null) {
                    return NodeOutput.failed("MISSING_FIELD",
                            "过激校验: " + row.get("period") + " 缺少可选字段 totalAssets/grossProfit");
                }
            }
        }
        checkResults.put("required-fields", "ok");

        // 5. 同一报告期多来源一致性：数值差异 > 0.5% 视为矛盾。
        Map<String, List<Map<String, Object>>> byPeriod = new LinkedHashMap<>();
        for (Map<String, Object> row : statements) {
            byPeriod.computeIfAbsent(String.valueOf(row.get("period")), k -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : byPeriod.entrySet()) {
            List<Map<String, Object>> rows = entry.getValue();
            if (rows.size() < 2) {
                continue;
            }
            for (int i = 1; i < rows.size(); i++) {
                if (differs(rows.get(0), rows.get(i))) {
                    return NodeOutput.failed("CONTRADICTORY_DATA",
                            "报告期 " + entry.getKey() + " 多来源数值矛盾: "
                                    + rows.get(0).get("source") + " vs " + rows.get(i).get("source"));
                }
            }
        }
        checkResults.put("cross-source-consistency", "ok");

        return NodeOutput.success(Map.of("validated", true, "checks", checkResults, "rows", statements.size()));
    }

    private boolean differs(Map<String, Object> a, Map<String, Object> b) {
        return differs(number(a.get("revenue")), number(b.get("revenue")))
                || differs(number(a.get("netProfit")), number(b.get("netProfit")));
    }

    private boolean differs(Double x, Double y) {
        if (x == null || y == null) {
            return !Objects.equals(x, y);
        }
        double base = Math.max(Math.abs(x), Math.abs(y));
        return Math.abs(x - y) > base * 0.005;
    }

    private Double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
