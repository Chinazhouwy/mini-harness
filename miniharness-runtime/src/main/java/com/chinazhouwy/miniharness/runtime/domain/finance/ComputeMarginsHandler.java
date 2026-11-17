package com.chinazhouwy.miniharness.runtime.domain.finance;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.workflow.NodeHandler;
import com.chinazhouwy.miniharness.runtime.workflow.NodeInput;
import com.chinazhouwy.miniharness.runtime.workflow.NodeOutput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 利润率计算节点（确定性金融计算，绝不把模型生成的数字当数据）。
 *
 * 已声明的 v1 缺陷（这也是自进化实验的起点）：
 * 本节点直接对报表行数值做跨期汇总（Σ净利/Σ收入），并假定单位为元。
 * 当数据存在单位不一致或报告期口径不一致时，汇总结果必然错误——
 * v1 版本没有任何前置校验来拦截这类输入；v2 通过在计算前插入口径验证节点修复。
 * 本节点的 Java 逻辑在 v1/v2 中完全相同：行为差异只来自流程结构，这正是要验证的命题。
 */
public class ComputeMarginsHandler implements NodeHandler {

    public static final String TYPE = "finance-compute-margins";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeOutput execute(NodeInput input) {
        String loadNode = String.valueOf(input.params().getOrDefault("loadNode", "load-financials"));
        NodeOutput loaded = input.upstreamOutputs().get(new NodeId(loadNode));
        if (loaded == null || loaded.data().isEmpty()) {
            return NodeOutput.failed("COMPUTE_INPUT_MISSING", "未找到上游数据节点 " + loadNode + " 的输出");
        }
        List<Map<String, Object>> statements =
                (List<Map<String, Object>>) loaded.data().getOrDefault("statements", List.of());
        if (statements.isEmpty()) {
            return NodeOutput.failed("MISSING_FIELD", "无报表行可计算");
        }

        double revenueSum = 0;
        double profitSum = 0;
        Map<String, Object> marginsByPeriod = new LinkedHashMap<>();
        String latestPeriod = null;
        Double latestMargin = null;
        Double previousMargin = null;
        List<String> unitWarnings = new ArrayList<>();

        for (Map<String, Object> row : statements) {
            Double revenue = asDouble(row.get("revenue"));
            Double netProfit = asDouble(row.get("netProfit"));
            if (revenue == null || netProfit == null) {
                // v1 对缺失字段不做防护——空值在这里触发计算失败，由评测揭示该缺陷。
                throw new IllegalStateException("字段缺失或非法: period=" + row.get("period")
                        + " revenue=" + revenue + " netProfit=" + netProfit);
            }
            if (!"yuan".equals(row.get("unit"))) {
                unitWarnings.add("行 " + row.get("period") + " 单位为 " + row.get("unit")
                        + "，本节点按元直接汇总（已知缺陷）");
            }
            revenueSum += revenue;
            profitSum += netProfit;
            double margin = netProfit / revenue;
            marginsByPeriod.put(String.valueOf(row.get("period")), margin);
            previousMargin = latestMargin;
            latestMargin = margin;
            latestPeriod = String.valueOf(row.get("period"));
        }

        double aggregatedMargin = revenueSum == 0 ? 0 : profitSum / revenueSum;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("netMarginLatest", aggregatedMargin);
        data.put("latestPeriod", latestPeriod);
        data.put("marginsByPeriod", marginsByPeriod);
        data.put("previousMargin", previousMargin);
        data.put("unitAssumed", "yuan");
        data.put("unitWarnings", unitWarnings);
        data.put("computationNote", "ΣnetProfit/Σrevenue 跨期汇总；未做单位换算与口径检查（v1 已知缺陷）");
        return NodeOutput.success(data);
    }

    private Double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
