package com.chinazhouwy.miniharness.finance.market.web;

import java.time.LocalDate;

/** 给前端图表使用的轻量历史点，不把完整原始数据暴露给页面。 */
public record LeverageHistoryPoint(
        LocalDate tradeDate,
        double leverageRatio,
        double clearanceFromPeak250,
        double marginGrowth60,
        double netRepaymentPressure20,
        double upRatio,
        double indexClose
) {
}
