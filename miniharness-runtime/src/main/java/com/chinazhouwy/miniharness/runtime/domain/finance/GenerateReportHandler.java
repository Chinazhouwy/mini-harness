package com.chinazhouwy.miniharness.runtime.domain.finance;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.workflow.NodeHandler;
import com.chinazhouwy.miniharness.runtime.workflow.NodeInput;
import com.chinazhouwy.miniharness.runtime.workflow.NodeOutput;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研究报告生成节点：输出结构化结论与完整证据链。
 * 结论文本由确定性规则生成（阈值比较），不是模型自由发挥；
 * 报告必须携带来源、报告期、单位与免责声明，缺失任何一项都视为不可交付。
 */
public class GenerateReportHandler implements NodeHandler {

    public static final String TYPE = "finance-report";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeOutput execute(NodeInput input) {
        String loadNode = String.valueOf(input.params().getOrDefault("loadNode", "load-financials"));
        String computeNode = String.valueOf(input.params().getOrDefault("computeNode", "compute-margins"));
        NodeOutput loaded = input.upstreamOutputs().get(new NodeId(loadNode));
        NodeOutput computed = input.upstreamOutputs().get(new NodeId(computeNode));
        if (loaded == null || computed == null) {
            return NodeOutput.failed("REPORT_INPUT_MISSING", "缺少数据或计算结果，无法生成报告");
        }
        String company = String.valueOf(loaded.data().getOrDefault("company", "未知公司"));
        String ticker = String.valueOf(loaded.data().getOrDefault("ticker", ""));
        Double netMarginLatest = (Double) computed.data().get("netMarginLatest");
        Double previousMargin = (Double) computed.data().get("previousMargin");
        if (netMarginLatest == null) {
            return NodeOutput.failed("REPORT_INPUT_MISSING", "缺少 netMarginLatest 指标");
        }

        String conclusion;
        if (previousMargin == null) {
            conclusion = "仅一期可比数据，净利率 " + format(netMarginLatest);
        } else {
            double delta = netMarginLatest - previousMargin;
            if (delta > 0.01) {
                conclusion = "盈利能力明显改善: 净利率 " + format(previousMargin) + " → " + format(netMarginLatest);
            } else if (delta < -0.01) {
                conclusion = "盈利能力明显下滑: 净利率 " + format(previousMargin) + " → " + format(netMarginLatest);
            } else {
                conclusion = "盈利能力基本稳定: 净利率 " + format(previousMargin) + " → " + format(netMarginLatest);
            }
        }

        List<Map<String, Object>> statements =
                (List<Map<String, Object>>) loaded.data().getOrDefault("statements", List.of());
        List<Map<String, Object>> provenance = new ArrayList<>();
        for (Map<String, Object> row : statements) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("period", row.get("period"));
            p.put("source", row.get("source"));
            p.put("sourceDate", row.get("sourceDate"));
            p.put("unit", row.get("unit"));
            provenance.add(p);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("company", company);
        report.put("ticker", ticker);
        report.put("metric", "netMargin(汇总口径)");
        report.put("netMarginLatest", netMarginLatest);
        report.put("marginsByPeriod", computed.data().get("marginsByPeriod"));
        report.put("conclusion", conclusion);
        report.put("provenance", provenance);
        report.put("generatedAt", Instant.now().toString());
        report.put("disclaimer", "合成数据演示，非真实研究结论，不构成投资建议");
        return NodeOutput.success(report);
    }

    private String format(double value) {
        return String.format("%.4f", value);
    }
}
