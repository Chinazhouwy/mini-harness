package com.quant.strategy.domain.repository.impl;

import com.quant.strategy.domain.record.MinuteKlineRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MinuteKlineRecordRepository 查询操作测试
 * 使用事务回滚确保测试数据不会污染数据库
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Transactional
@Sql(scripts = {
    "/schema-test.sql",
    "/data-stock-info-test.sql"
}, config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
class MinuteKlineRecordRepositoryQueryTest {
    
    @Autowired
    private MinuteKlineRecordRepositoryImpl repository;
    
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;
    
    @BeforeEach
    void setUp() {
        // 验证测试数据已正确加载
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM harness_db.minute_kline", java.util.Map.of(), Long.class);
        assertNotNull(count, "测试数据应该已加载");
    }
    
    @Test
    void testFindByTradeTimeAndStockCode_ExistingData() {
        LocalDateTime tradeTime = LocalDateTime.of(2024, 3, 1, 9, 30);
        String stockCode = "000001";
        
        Optional<MinuteKlineRecord> result = repository.findByTradeTimeAndStockCode(tradeTime, stockCode);
        
        assertTrue(result.isPresent(), "应该找到对应的分钟K线数据");
        MinuteKlineRecord record = result.get();
        assertEquals(tradeTime, record.tradeTime());
        assertEquals(stockCode, record.stockCode());
        assertEquals(new BigDecimal("12.50"), record.open());
        assertEquals(new BigDecimal("12.52"), record.high());
        assertEquals(new BigDecimal("12.48"), record.low());
        assertEquals(new BigDecimal("12.51"), record.close());
        assertEquals(1500000L, record.volume());
        assertEquals(new BigDecimal("18750000.0000"), record.amount());
    }
    
    @Test
    void testFindByTradeTimeAndStockCode_NonExistingData() {
        LocalDateTime tradeTime = LocalDateTime.of(2024, 1, 1, 9, 30);
        String stockCode = "000001";
        
        Optional<MinuteKlineRecord> result = repository.findByTradeTimeAndStockCode(tradeTime, stockCode);
        
        assertFalse(result.isPresent(), "不应该找到不存在的分钟K线数据");
    }
    
    @Test
    void testFindByStockCode_ExistingStock() {
        String stockCode = "000001";
        
        List<MinuteKlineRecord> results = repository.findByStockCode(stockCode);
        
        assertFalse(results.isEmpty(), "应该找到对应股票的分钟K线数据");
        assertEquals(5, results.size(), "000001股票应该有5分钟的分钟K线数据");
        
        // 验证数据按时间倒序排列
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(
                results.get(i).tradeTime().isAfter(results.get(i + 1).tradeTime()),
                "数据应该按时间倒序排列"
            );
        }
        
        // 验证第一条数据是最新时间
        MinuteKlineRecord latestRecord = results.get(0);
        assertEquals(LocalDateTime.of(2024, 3, 1, 9, 34), latestRecord.tradeTime());
    }
    
    @Test
    void testFindByStockCode_NonExistingStock() {
        String stockCode = "999999";
        
        List<MinuteKlineRecord> results = repository.findByStockCode(stockCode);
        
        assertTrue(results.isEmpty(), "不应该找到不存在股票的分钟K线数据");
    }
    
    @Test
    void testFindByTradeTimeBetween_ValidRange() {
        LocalDateTime startTime = LocalDateTime.of(2024, 3, 1, 9, 31);
        LocalDateTime endTime = LocalDateTime.of(2024, 3, 1, 9, 33);
        
        List<MinuteKlineRecord> results = repository.findByTradeTimeBetween(startTime, endTime);
        
        assertFalse(results.isEmpty(), "应该找到时间范围内的分钟K线数据");
        assertEquals(3, results.size(), "应该找到3条分钟K线数据");
        
        // 验证所有结果都在时间范围内
        for (MinuteKlineRecord record : results) {
            assertTrue(
                !record.tradeTime().isBefore(startTime) && !record.tradeTime().isAfter(endTime),
                "所有结果都应该在指定时间范围内"
            );
        }
    }
    
    @Test
    void testFindByTradeTimeBetween_NoDataInRange() {
        LocalDateTime startTime = LocalDateTime.of(2024, 1, 1, 9, 0);
        LocalDateTime endTime = LocalDateTime.of(2024, 1, 1, 9, 59);
        
        List<MinuteKlineRecord> results = repository.findByTradeTimeBetween(startTime, endTime);
        
        assertTrue(results.isEmpty(), "不应该找到时间范围外的分钟K线数据");
    }
    
    @Test
    void testFindByStockCodeAndTradeTimeBetween_ValidData() {
        String stockCode = "000001";
        LocalDateTime startTime = LocalDateTime.of(2024, 3, 1, 9, 31);
        LocalDateTime endTime = LocalDateTime.of(2024, 3, 1, 9, 33);
        
        List<MinuteKlineRecord> results = repository.findByStockCodeAndTradeTimeBetween(stockCode, startTime, endTime);
        
        assertFalse(results.isEmpty(), "应该找到指定股票在时间范围内的分钟K线数据");
        assertEquals(3, results.size(), "000001股票在09:31到09:33期间应该有3分钟的数据");
        
        // 验证所有结果都是指定股票且在时间范围内
        for (MinuteKlineRecord record : results) {
            assertEquals(stockCode, record.stockCode(), "所有结果都应该是000001股票");
            assertTrue(
                !record.tradeTime().isBefore(startTime) && !record.tradeTime().isAfter(endTime),
                "所有结果都应该在指定时间范围内"
            );
        }
    }
    
    @Test
    void testFindLatestByStockCode_ExistingStock() {
        String stockCode = "000001";
        
        Optional<MinuteKlineRecord> result = repository.findLatestByStockCode(stockCode);
        
        assertTrue(result.isPresent(), "应该找到最新分钟K线数据");
        assertEquals(LocalDateTime.of(2024, 3, 1, 9, 34), result.get().tradeTime(), "最新时间应该是09:34");
    }
    
    @Test
    void testFindLatestByStockCode_NonExistingStock() {
        String stockCode = "999999";
        
        Optional<MinuteKlineRecord> result = repository.findLatestByStockCode(stockCode);
        
        assertFalse(result.isPresent(), "不应该找到不存在股票的最新分钟K线数据");
    }
    
    @Test
    void testExistsByTradeTimeAndStockCode_ExistingData() {
        LocalDateTime tradeTime = LocalDateTime.of(2024, 3, 1, 9, 30);
        String stockCode = "000001";
        
        boolean exists = repository.existsByTradeTimeAndStockCode(tradeTime, stockCode);
        
        assertTrue(exists, "应该确认存在的分钟K线数据");
    }
    
    @Test
    void testExistsByTradeTimeAndStockCode_NonExistingData() {
        LocalDateTime tradeTime = LocalDateTime.of(2024, 1, 1, 9, 30);
        String stockCode = "000001";
        
        boolean exists = repository.existsByTradeTimeAndStockCode(tradeTime, stockCode);
        
        assertFalse(exists, "应该确认不存在的分钟K线数据");
    }
    
    @Test
    void testCountByStockCode_ExistingStock() {
        String stockCode = "000001";
        
        long count = repository.countByStockCode(stockCode);
        
        assertEquals(5, count, "000001股票应该有5分钟的分钟K线数据");
    }
    
    @Test
    void testCountByStockCode_NonExistingStock() {
        String stockCode = "999999";
        
        long count = repository.countByStockCode(stockCode);
        
        assertEquals(0, count, "不存在股票应该有0分钟的分钟K线数据");
    }
    
    @Test
    void testFindUpKlinesByStockCode() {
        String stockCode = "000001";
        
        List<MinuteKlineRecord> results = repository.findUpKlinesByStockCode(stockCode);
        
        // 验证所有结果都是上涨K线（收盘价大于开盘价）
        for (MinuteKlineRecord record : results) {
            assertTrue(record.close().compareTo(record.open()) > 0, "所有结果都应该是上涨K线");
        }
    }
    
    @Test
    void testFindDownKlinesByStockCode() {
        String stockCode = "000001";
        
        List<MinuteKlineRecord> results = repository.findDownKlinesByStockCode(stockCode);
        
        // 验证所有结果都是下跌K线（收盘价小于开盘价）
        for (MinuteKlineRecord record : results) {
            assertTrue(record.close().compareTo(record.open()) < 0, "所有结果都应该是下跌K线");
        }
    }
    
    @Test
    void testFindFlatKlinesByStockCode() {
        String stockCode = "000001";
        
        List<MinuteKlineRecord> results = repository.findFlatKlinesByStockCode(stockCode);
        
        // 验证所有结果都是平盘K线（收盘价等于开盘价）
        for (MinuteKlineRecord record : results) {
            assertEquals(0, record.close().compareTo(record.open()), "所有结果都应该是平盘K线");
        }
    }
    
    @Test
    void testFindByAmountGreaterThan() {
        BigDecimal minAmount = new BigDecimal("17000000");
        
        List<MinuteKlineRecord> results = repository.findByAmountGreaterThan(minAmount);
        
        // 验证所有结果成交额都大于等于指定值
        for (MinuteKlineRecord record : results) {
            assertTrue(record.amount().compareTo(minAmount) >= 0, "所有结果成交额都应该大于等于指定值");
        }
        
        // 验证按成交额倒序排列
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(
                results.get(i).amount().compareTo(results.get(i + 1).amount()) >= 0,
                "数据应该按成交额倒序排列"
            );
        }
    }
    
    @Test
    void testFindByTradeDate() {
        LocalDateTime tradeDate = LocalDateTime.of(2024, 3, 1, 0, 0);
        
        List<MinuteKlineRecord> results = repository.findByTradeDate(tradeDate);
        
        assertFalse(results.isEmpty(), "应该找到指定日期的分钟K线数据");
        
        // 验证所有结果都是指定日期
        for (MinuteKlineRecord record : results) {
            assertEquals(tradeDate.toLocalDate(), record.tradeTime().toLocalDate(), "所有结果都应该是指定日期");
        }
    }
}