package com.quant.strategy.domain.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票基本信息 Record
 * 对应 ClickHouse 中的 stock_info 表
 * 
 * @param stockCode 股票代码，如 '600406'
 * @param shortName 股票简称，如 '平安银行'
 * @param fullName 股票全名（可为空）
 * @param exchange 交易所：'SZ'（深交所），'SH'（上交所），'BJ'（北交所）
 * @param marketCap 总市值（亿元），可为空
 * @param industry 行业分类，可为空
 * @param region 地域，可为空
 * @param listingDate 上市日期，可为空
 * @param isActive 是否活跃（1=活跃，0=退市）
 * @param updateTime 更新时间，由数据库自动设置
 */
public record StockInfoRecord(
    String stockCode,
    String shortName,
    String fullName,
    String exchange,
    BigDecimal marketCap,
    String industry,
    String region,
    LocalDate listingDate,
    Integer isActive,
    LocalDateTime updateTime
) {
    
    /**
     * 为 Jackson 反序列化提供自定义构造函数
     * 因为 record 的默认构造函数可能无法正确反序列化
     */
    @JsonCreator
    public StockInfoRecord(
        @JsonProperty("stockCode") String stockCode,
        @JsonProperty("shortName") String shortName,
        @JsonProperty("fullName") String fullName,
        @JsonProperty("exchange") String exchange,
        @JsonProperty("marketCap") BigDecimal marketCap,
        @JsonProperty("industry") String industry,
        @JsonProperty("region") String region,
        @JsonProperty("listingDate") LocalDate listingDate,
        @JsonProperty("isActive") Integer isActive,
        @JsonProperty("updateTime") LocalDateTime updateTime
    ) {
        this.stockCode = stockCode;
        this.shortName = shortName;
        this.fullName = fullName;
        this.exchange = exchange;
        this.marketCap = marketCap;
        this.industry = industry;
        this.region = region;
        this.listingDate = listingDate;
        this.isActive = isActive;
        this.updateTime = updateTime;
    }
    
    /**
     * 获取交易所中文名称
     */
    public String getExchangeName() {
        return switch (exchange) {
            case "SH" -> "上海证券交易所";
            case "SZ" -> "深圳证券交易所";
            case "BJ" -> "北京证券交易所";
            default -> "未知交易所";
        };
    }
    
    /**
     * 判断股票是否活跃
     */
    public boolean isActiveStock() {
        return isActive != null && isActive == 1;
    }
    
    /**
     * 获取完整的股票标识（交易所+代码）
     */
    public String getFullStockCode() {
        return exchange + "." + stockCode;
    }
    
    /**
     * 创建一个新的StockInfoRecord，更新指定字段
     */
    public StockInfoRecord withStockCode(String newStockCode) {
        return new StockInfoRecord(
            newStockCode, shortName, fullName, exchange, marketCap, 
            industry, region, listingDate, isActive, updateTime
        );
    }
    
    /**
     * 创建一个新的StockInfoRecord，更新指定字段
     */
    public StockInfoRecord withShortName(String newShortName) {
        return new StockInfoRecord(
            stockCode, newShortName, fullName, exchange, marketCap, 
            industry, region, listingDate, isActive, updateTime
        );
    }
    
    /**
     * 创建一个新的StockInfoRecord，更新指定字段
     */
    public StockInfoRecord withExchange(String newExchange) {
        return new StockInfoRecord(
            stockCode, shortName, fullName, newExchange, marketCap, 
            industry, region, listingDate, isActive, updateTime
        );
    }
}