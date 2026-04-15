package com.quant.strategy.domain.repository;

import com.quant.strategy.domain.record.DailyKlineRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 日K线行情Record Repository接口
 * 只包含查询操作，不包含新增、更新、删除操作
 */
public interface DailyKlineRecordRepository {
    
    /**
     * 根据日期和股票代码查找
     */
    Optional<DailyKlineRecord> findByTradeDateAndStockCode(LocalDate tradeDate, String stockCode);
    
    /**
     * 根据股票代码查找所有日K线数据
     */
    List<DailyKlineRecord> findByStockCode(String stockCode);
    
    /**
     * 根据日期范围查找数据
     */
    List<DailyKlineRecord> findByTradeDateBetween(LocalDate startDate, LocalDate endDate);
    
    /**
     * 根据股票代码和日期范围查找数据
     */
    List<DailyKlineRecord> findByStockCodeAndTradeDateBetween(String stockCode, LocalDate startDate, LocalDate endDate);
    
    /**
     * 根据股票代码查找最新数据
     */
    Optional<DailyKlineRecord> findLatestByStockCode(String stockCode);
    
    /**
     * 判断数据是否存在
     */
    boolean existsByTradeDateAndStockCode(LocalDate tradeDate, String stockCode);
    
    /**
     * 统计指定股票的日K线数量
     */
    long countByStockCode(String stockCode);
    
    /**
     * 查找上涨日数据
     */
    List<DailyKlineRecord> findUpDaysByStockCode(String stockCode);
    
    /**
     * 查找下跌日数据
     */
    List<DailyKlineRecord> findDownDaysByStockCode(String stockCode);
    
    /**
     * 查找指定股票的价格最高记录
     */
    Optional<DailyKlineRecord> findHighestPriceByStockCode(String stockCode);
    
    /**
     * 查找指定股票的价格最低记录
     */
    Optional<DailyKlineRecord> findLowestPriceByStockCode(String stockCode);
    
    /**
     * 根据交易所查找股票的最新日K线
     */
    List<DailyKlineRecord> findLatestByExchange(String exchange);
    
    /**
     * 查找指定日期的所有股票数据
     */
    List<DailyKlineRecord> findByTradeDate(LocalDate tradeDate);
    
    /**
     * 查找成交额大于指定值的数据
     */
    List<DailyKlineRecord> findByAmountGreaterThan(BigDecimal minAmount);
}