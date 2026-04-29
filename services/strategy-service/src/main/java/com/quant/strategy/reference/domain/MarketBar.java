package com.quant.strategy.reference.domain;

import com.quant.strategy.domain.record.DailyKlineRecord;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Reference slice 内部使用的行情模型。
 *
 * <p>为什么不直接把 {@link DailyKlineRecord} 传到策略里：
 * Repository record 是数据库视角，字段允许为空，也带有 ClickHouse 表结构细节；
 * 策略模型是业务视角，要求 OHLCV 至少可计算。中间隔一层可以让策略代码
 * 少知道数据库细节，后续换数据源时也更轻。</p>
 */
public record MarketBar(
    LocalDate tradeDate,
    String stockCode,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume
) {

    public static MarketBar from(DailyKlineRecord record) {
        return new MarketBar(
            record.tradeDate(),
            record.stockCode(),
            required(record.open(), "open"),
            required(record.high(), "high"),
            required(record.low(), "low"),
            required(record.close(), "close"),
            record.volume() == null ? 0L : record.volume()
        );
    }

    private static BigDecimal required(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Daily kline field is required: " + field);
        }
        return value;
    }
}
