package com.quant.strategy.domain.repository;

import com.quant.strategy.domain.record.StockInfoRecord;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 股票基本信息Record Repository接口
 * 只包含查询操作，不包含增删改操作
 */
public interface StockInfoRecordRepository {
    
    /**
     * 根据股票代码查找
     */
    Optional<StockInfoRecord> findByStockCode(String stockCode);
    
    /**
     * 查找所有股票信息
     */
    List<StockInfoRecord> findAll();
    
    /**
     * 根据交易所查找股票
     */
    List<StockInfoRecord> findByExchange(String exchange);
    
    /**
     * 根据行业查找股票
     */
    List<StockInfoRecord> findByIndustry(String industry);
    
    /**
     * 判断股票是否存在
     */
    boolean existsByStockCode(String stockCode);
    
    /**
     * 统计股票数量
     */
    long count();
    
    /**
     * 查找活跃股票
     */
    List<StockInfoRecord> findActiveStocks();
    
    /**
     * 根据多个股票代码批量查找
     */
    List<StockInfoRecord> findByStockCodes(List<String> stockCodes);
    
    /**
     * 查找指定交易所的活跃股票
     */
    List<StockInfoRecord> findActiveStocksByExchange(String exchange);
    
    /**
     * 根据地域查找股票
     */
    List<StockInfoRecord> findStocksByRegion(String region);
    
    /**
     * 根据市值范围查找股票
     */
    List<StockInfoRecord> findStocksByCapRange(BigDecimal minCap, BigDecimal maxCap);
}