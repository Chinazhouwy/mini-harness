package com.quant.strategy.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.ta4j.core.AnalysisCriterion;
import org.ta4j.core.BarSeriesBuilder;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.analysis.cost.CostModel;
import org.ta4j.core.analysis.cost.FixedTransactionCostModel;
import org.ta4j.core.analysis.cost.ZeroCostModel;
import org.ta4j.core.criteria.NumberOfPositionsCriterion;
import org.ta4j.core.criteria.ReturnOverMaxDrawdownCriterion;
import org.ta4j.core.criteria.pnl.NetReturnCriterion;

import java.util.List;


@Configuration
public class Ta4jConfig {

    /**
     * 默认使用的分析标准集合（可注入到回测服务中）
     */
    @Bean
    public List<AnalysisCriterion> defaultAnalysisCriteria() {
        return List.of(
                new NetReturnCriterion(),               // 净收益率
                new ReturnOverMaxDrawdownCriterion(),   // 回报/最大回撤比
                new NumberOfPositionsCriterion()        // 交易次数（仓位数量）
                // 注意：以下类在ta4j 0.18中可能不可用或已重命名：
                // - TotalReturnCriterion (使用NetReturnCriterion替代)
                // - SharpeRatioCriterion (需要升级到0.22+)
                // - MaximumDrawdownCriterion (已过时，使用ReturnOverMaxDrawdownCriterion)
                // - AverageProfitableTradesCriterion (不存在)
                // - AverageLossTradesCriterion (不存在)
        );
    }

    /**
     * 如果需要自定义 BarSeries 构造器（例如从 Redis 快照构建），可以在此声明
     */
    @Bean
    public BarSeriesBuilder barSeriesBuilder() {
        return new BaseBarSeriesBuilder();
    }

    /**
     * 配置交易成本模型（固定佣金）
     */
    @Bean
    public CostModel transactionCostModel() {
        return new FixedTransactionCostModel(0.001); // 0.1% 交易费用
    }

    /**
     * 配置持仓成本模型（默认为零成本）
     */
    @Bean
    public CostModel holdingCostModel() {
        return new ZeroCostModel();
    }
}