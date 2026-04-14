package com.quant.strategy.domain.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分钟K线行情 Record
 * 对应 ClickHouse 中的 minute_kline 表
 * 
 * @param tradeTime 交易时间（精确到分钟）
 * @param stockCode 股票代码，如 '600406'
 * @param intervalType 时间间隔: 1=1分钟, 5=5分钟, 15=15分钟, 30=30分钟, 60=60分钟
 * @param open 开盘价，可为空
 * @param high 最高价，可为空
 * @param low 最低价，可为空
 * @param close 收盘价，可为空
 * @param volume 成交量（股），可为空
 * @param amount 成交金额（万元），可为空
 * @param updateTime 更新时间，由数据库自动设置
 */
public record MinuteKlineRecord(
    LocalDateTime tradeTime,
    String stockCode,
    Integer intervalType,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    Long volume,
    BigDecimal amount,
    LocalDateTime updateTime
) {
    
    /**
     * 为 Jackson 反序列化提供自定义构造函数
     * 因为 record 的默认构造函数可能无法正确反序列化
     */
    @JsonCreator
    public MinuteKlineRecord(
        @JsonProperty("tradeTime") LocalDateTime tradeTime,
        @JsonProperty("stockCode") String stockCode,
        @JsonProperty("intervalType") Integer intervalType,
        @JsonProperty("open") BigDecimal open,
        @JsonProperty("high") BigDecimal high,
        @JsonProperty("low") BigDecimal low,
        @JsonProperty("close") BigDecimal close,
        @JsonProperty("volume") Long volume,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("updateTime") LocalDateTime updateTime
    ) {
        this.tradeTime = tradeTime;
        this.stockCode = stockCode;
        this.intervalType = intervalType;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.amount = amount;
        this.updateTime = updateTime;
    }
    
    /**
     * 获取时间间隔描述
     */
    public String getIntervalDescription() {
        return switch (intervalType) {
            case 1 -> "1分钟";
            case 5 -> "5分钟";
            case 15 -> "15分钟";
            case 30 -> "30分钟";
            case 60 -> "60分钟";
            default -> "未知间隔";
        };
    }
    
    /**
     * 计算成交额（元）
     */
    public BigDecimal getAmountInYuan() {
        return amount != null ? amount.multiply(new BigDecimal("10000")) : BigDecimal.ZERO;
    }
    
    /**
     * 计算价格范围（最高-最低）
     */
    public BigDecimal getPriceRange() {
        if (high != null && low != null) {
            return high.subtract(low);
        }
        return BigDecimal.ZERO;
    }
    
    /**
     * 计算K线实体大小（收盘-开盘）
     */
    public BigDecimal getEntitySize() {
        if (close != null && open != null) {
            return close.subtract(open);
        }
        return BigDecimal.ZERO;
    }
    
    /**
     * 判断是否为上涨K线
     */
    public boolean isUpKline() {
        return getEntitySize().compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * 判断是否为下跌K线
     */
    public boolean isDownKline() {
        return getEntitySize().compareTo(BigDecimal.ZERO) < 0;
    }
    
    /**
     * 判断是否为平盘K线
     */
    public boolean isFlatKline() {
        return getEntitySize().compareTo(BigDecimal.ZERO) == 0;
    }
}