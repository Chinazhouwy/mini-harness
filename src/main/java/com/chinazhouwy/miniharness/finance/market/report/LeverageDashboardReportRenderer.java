package com.chinazhouwy.miniharness.finance.market.report;

import com.chinazhouwy.miniharness.finance.market.domain.LeverageMetricSnapshot;
import com.chinazhouwy.miniharness.finance.market.domain.LeverageScoreSnapshot;
import com.chinazhouwy.miniharness.finance.market.domain.MarketLeverageDataset;
import com.chinazhouwy.miniharness.finance.market.domain.SourceEvidence;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** 只输出事实、评分、证据和限制，不输出买卖指令。 */
public final class LeverageDashboardReportRenderer {

    public String render(
            MarketLeverageDataset dataset,
            List<LeverageMetricSnapshot> metrics,
            LeverageScoreSnapshot score
    ) {
        LeverageMetricSnapshot latest = metrics.getLast();
        StringBuilder report = new StringBuilder();
        report.append("# 公开可观测 A 股杠杆温度计\n\n");
        report.append("> 这是研究框架输出，不是买卖建议。\n\n");
        report.append("- 市场：").append(dataset.market()).append('\n');
        LocalDate firstDate = dataset.observations().stream()
                .map(observation -> observation.tradeDate())
                .min(LocalDate::compareTo)
                .orElseThrow();
        report.append("- 数据区间：").append(firstDate)
                .append(" ~ ").append(latest.tradeDate()).append('\n');
        report.append("- 有效指标快照：").append(metrics.size()).append(" 个交易日\n\n");

        report.append("## 当前状态\n\n");
        report.append("- 状态：**").append(score.regime()).append("**\n");
        report.append("- 杠杆过热评分：").append(format(score.heatScore())).append(" / 100\n");
        report.append("- 杠杆出清评分：").append(format(score.clearScore())).append(" / 100\n");
        report.append("- 止跌确认评分：").append(format(score.confirmationScore())).append(" / 100\n\n");

        report.append("## 最新指标\n\n");
        report.append("| 指标 | 数值 | 含义 |\n|---|---:|---|\n");
        row(report, "融资余额", money(latest.financingBalanceYuan()), "公开融资存量");
        row(report, "融资余额 / 流通市值", percent(latest.leverageRatio()), "杠杆存量强度");
        row(report, "融资买入额 / 市场成交额", percent(latest.buyIntensity()), "增量融资交易强度");
        row(report, "60 日融资余额变化", percent(latest.marginGrowth60()), "杠杆扩张或收缩速度");
        row(report, "距 250 日融资高点回撤", percent(latest.clearanceFromPeak250()), "历史高点后的出清幅度");
        row(report, "20 日净偿还压力", percent(latest.netRepaymentPressure20()), "偿还额相对存量余额的压力");
        row(report, "20 日实现波动率", percent(latest.realizedVolatility20()), "指数价格波动");
        row(report, "上涨股票比例", percent(latest.upRatio()), "市场宽度");
        report.append('\n');

        report.append("## 评分\n\n");
        report.append("| 评分项 | 分位数/评分 |\n|---|---:|\n");
        scoreRow(report, "杠杆比例历史分位", score.leveragePercentile());
        scoreRow(report, "融资买入强度历史分位", score.buyIntensityPercentile());
        scoreRow(report, "60 日融资扩张热度分位", score.marginGrowth60Percentile());
        scoreRow(report, "融资高点回撤历史分位", score.clearancePercentile());
        scoreRow(report, "净偿还压力历史分位", score.repaymentPercentile());
        scoreRow(report, "波动率历史分位", score.volatilityPercentile());
        scoreRow(report, "Heat", score.heatScore());
        scoreRow(report, "Clear", score.clearScore());
        scoreRow(report, "Confirmation", score.confirmationScore());
        report.append('\n');

        report.append("## 计算证据\n\n");
        for (String evidence : score.evidence()) {
            report.append("- ").append(evidence).append('\n');
        }
        report.append('\n');

        report.append("## 数据来源\n\n");
        for (SourceEvidence evidence : dataset.sources()) {
            report.append("- ").append(evidence.name())
                    .append("：").append(evidence.uri())
                    .append("，数据日期 ").append(evidence.dataDate())
                    .append("，字段 ").append(evidence.field())
                    .append("，单位 ").append(evidence.unit()).append('\n');
        }
        report.append('\n');

        report.append("## 解释边界\n\n");
        report.append("- 杠杆余额下降不等于市场已经见底；需要结合价格是否停止创新低和市场宽度恢复。\n");
        report.append("- 公开融资数据不能覆盖场外配资、机构内部风险预算和全部衍生品杠杆。\n");
        report.append("- 分位数和评分权重是当前版本的可追踪规则，后续需要用历史样本回测，而不是视为永久阈值。\n\n");

        report.append("## 风险与限制\n\n");
        report.append("- 当前数据源是确定性 Fixture，只用于验证 Harness 闭环，不代表实时行情。\n");
        report.append("- 指标口径、单位、交易日和数据延迟需要在接入交易所适配器时再次核对。\n");
        report.append("- 本报告不构成投资建议，也不自动产生建仓或清仓指令。\n");
        return report.toString();
    }

    private static void row(StringBuilder report, String name, String value, String meaning) {
        report.append('|').append(name).append('|').append(value).append('|').append(meaning).append('|').append('\n');
    }

    private static void scoreRow(StringBuilder report, String name, double value) {
        report.append('|').append(name).append('|').append(format(value)).append('|').append('\n');
    }

    private static String money(long yuan) {
        return String.format(Locale.ROOT, "%.2f 亿元", yuan / 100_000_000.0);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
