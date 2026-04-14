package com.quant.strategy.domain.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日K线行情 Record
 * 对应 ClickHouse 中的 daily_kline 表
 * 
 * @param tradeDate 交易日期
 * @param stockCode 股票代码，如 '600406'
 * @param preClose 前收盘价，可为空
 * @param open 开盘价，可为空
 * @param high 最高价，可为空
 * @param low 最低价，可为空
 * @param close 收盘价，可为空
 * @param volume 成交量（股），可为空
 * @param amount 成交金额（万元），可为空
 * @param turnoverRate 换手率（%），可为空
 * @param change 涨跌额，可为空
 * @param changePct 涨跌幅（%），可为空
 * @param amplitude 振幅（%），可为空
 * @param updateTime 更新时间，由数据库自动设置
 */
public record DailyKlineRecord(
    LocalDate tradeDate,
    String stockCode,
    BigDecimal preClose,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    Long volume,
    BigDecimal amount,
    BigDecimal turnoverRate,
    BigDecimal change,
    BigDecimal changePct,
    BigDecimal amplitude,
    LocalDateTime updateTime
) {
    
    /**
     * 为 Jackson 反序列化提供自定义构造函数
     * 因为 record 的默认构造函数可能无法正确反序列化
     */
    @JsonCreator
    public DailyKlineRecord(
        @JsonProperty("tradeDate") LocalDate tradeDate,
        @JsonProperty("stockCode") String stockCode,
        @JsonProperty("preClose") BigDecimal preClose,
        @JsonProperty("open") BigDecimal open,
        @JsonProperty("high") BigDecimal high,
        @JsonProperty("low") BigDecimal low,
        @JsonProperty("close") BigDecimal close,
        @JsonProperty("volume") Long volume,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("turnoverRate") BigDecimal turnoverRate,
        @JsonProperty("change") BigDecimal change,
        @JsonProperty("changePct") BigDecimal changePct,
        @JsonProperty("amplitude") BigDecimal amplitude,
        @JsonProperty("updateTime") LocalDateTime updateTime
    ) {
        this.tradeDate = tradeDate;
        this.stockCode = stockCode;
        this.preClose = preClose;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.amount = amount;
        this.turnoverRate = turnoverRate;
        this.change = change;
        this.changePct = changePct;
        this.amplitude = amplitude;
        this.updateTime = updateTime;
    }
    
    /**
     * 计算成交额（元）
     */
    public BigDecimal getAmountInYuan() {
        return amount != null ? amount.multiply(new BigDecimal("10000")) : BigDecimal.ZERO;
    }
    
    /**
     * 判断是否为上涨日
     */
    public boolean isUpDay() {
        return change != null && change.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * 判断是否为下跌日
     */
    public boolean isDownDay() {
        return change != null && change.compareTo(BigDecimal.ZERO) < 0;
    }
    
    /**
     * 判断是否为平盘日
     */
    public boolean isFlatDay() {
        return change != null && change.compareTo(BigDecimal.ZERO) == 0;
    }
    
    /**
     * 计算实体大小（收盘价与开盘价之差）
     */
    public BigDecimal getEntitySize() {
        if (open != null && close != null) {
            return close.subtract(open).abs();
        }
        return BigDecimal.ZERO;
    }
    
    /**
     * 计算影线大小
     */
    public BigDecimal getShadowSize() {
        if (high != null && low != null && open != null && close != null) {
            BigDecimal upperShadow = high.subtract(open.compareTo(close) > 0 ? open : close);
            BigDecimal lowerShadow = (open.compareTo(close) < 0 ? open : close).subtract(low);
            return upperShadow.add(lowerShadow);
        }
        return BigDecimal.ZERO;
    }
}