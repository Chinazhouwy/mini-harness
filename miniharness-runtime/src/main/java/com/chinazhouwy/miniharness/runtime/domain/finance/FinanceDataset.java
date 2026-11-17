package com.chinazhouwy.miniharness.runtime.domain.finance;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 合成金融数据集访问器（明确标注：全部为合成数据，非真实行情，非投资建议）。
 * 案例文件位于 classpath: finance/cases/{caseId}.json，每份自带缺陷说明，保证实验可重复。
 */
public final class FinanceDataset {

    /** 单条财务报表行：保留来源、报告期、单位——口径信息与数值同等重要。 */
    public record StatementRow(
            String period,
            String unit,
            String source,
            String sourceDate,
            Double revenue,
            Double netProfit
    ) {
    }

    public record FinanceCase(
            String caseId,
            String company,
            String ticker,
            String injectedDefect,
            String notes,
            List<StatementRow> statements
    ) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FinanceDataset() {
    }

    public static FinanceCase loadCase(String caseId) {
        String path = "finance/cases/" + caseId + ".json";
        try (InputStream in = FinanceDataset.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("合成案例不存在: " + path);
            }
            Map<String, Object> raw = MAPPER.readValue(in, Map.class);
            return parse(raw);
        } catch (IOException e) {
            throw new IllegalStateException("读取合成案例失败: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    static FinanceCase parse(Map<String, Object> raw) {
        List<Map<String, Object>> rawStatements = (List<Map<String, Object>>) raw.get("statements");
        List<StatementRow> rows = rawStatements.stream()
                .map(row -> new StatementRow(
                        (String) row.get("period"),
                        (String) row.get("unit"),
                        (String) row.get("source"),
                        (String) row.get("sourceDate"),
                        row.get("revenue") instanceof Number n ? n.doubleValue() : null,
                        row.get("netProfit") instanceof Number n ? n.doubleValue() : null))
                .toList();
        return new FinanceCase((String) raw.get("caseId"), (String) raw.get("company"),
                (String) raw.get("ticker"), (String) raw.get("injectedDefect"),
                (String) raw.get("notes"), rows);
    }
}
