package com.quant.strategy.domain.repository;

import com.quant.strategy.domain.record.MinuteKlineRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 分钟K线行情Record Repository接口
 * 只包含查询操作，不包含新增、更新、删除操作
 */
public interface MinuteKlineRecordRepository {
    
    Optional<MinuteKlineRecord> findByTradeTimeAndStockCode(LocalDateTime tradeTime, String stockCode);
    
    List<MinuteKlineRecord> findByStockCode(String stockCode);
    
    List<MinuteKlineRecord> findByTradeTimeBetween(LocalDateTime startTime, LocalDateTime endTime);
    
    List<MinuteKlineRecord> findByStockCodeAndTradeTimeBetween(String stockCode, LocalDateTime startTime, LocalDateTime endTime);
    
    List<MinuteKlineRecord> findByStockCodeAndIntervalType(String stockCode, Integer intervalType);
    
    Optional<MinuteKlineRecord> findLatestByStockCode(String stockCode);
    
    boolean existsByTradeTimeAndStockCode(LocalDateTime tradeTime, String stockCode);
    
    long countByStockCode(String stockCode);
    
    List<MinuteKlineRecord> findByAmountGreaterThan(BigDecimal minAmount);
    
    List<MinuteKlineRecord> findByIntervalType(Integer intervalType);
    
    List<MinuteKlineRecord> findByTradeDate(LocalDateTime tradeDate);
    
    List<MinuteKlineRecord> findByStockCodeAndIntervalTypeAndTradeTimeBetween(String stockCode, Integer intervalType, LocalDateTime startTime, LocalDateTime endTime);
    
    List<MinuteKlineRecord> findUpKlinesByStockCode(String stockCode);
    
    List<MinuteKlineRecord> findDownKlinesByStockCode(String stockCode);
    
    List<MinuteKlineRecord> findFlatKlinesByStockCode(String stockCode);
}