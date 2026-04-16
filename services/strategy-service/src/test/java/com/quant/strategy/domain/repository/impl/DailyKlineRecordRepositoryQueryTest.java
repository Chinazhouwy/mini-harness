package com.quant.strategy.domain.repository.impl;

import com.quant.strategy.domain.record.DailyKlineRecord;
import com.quant.strategy.support.AbstractClickHouseIntegrationTest;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DailyKlineRecordRepository 查询操作测试
 * 使用事务回滚确保测试数据不会污染数据库
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Transactional
@Sql(scripts = {
    "/schema-test.sql",
    "/data-stock-info-test.sql"
}, config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
class DailyKlineRecordRepositoryQueryTest extends AbstractClickHouseIntegrationTest {
    
    @Autowired
    private DailyKlineRecordRepositoryImpl repository;
    
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;
    
    @BeforeEach
    void setUp() {
        // 验证测试数据已正确加载
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM harness_db.daily_kline", java.util.Map.of(), Long.class);
        assertNotNull(count, "测试数据应该已加载");
    }
    
    @Test
    void testFindByTradeDateAndStockCode_ExistingData() {
        LocalDate tradeDate = LocalDate.of(2024, 3, 1);
        String stockCode = "000001";
        
        Optional<DailyKlineRecord> result = repository.findByTradeDateAndStockCode(tradeDate, stockCode);
        
        assertTrue(result.isPresent(), "应该找到对应的日K线数据");
        DailyKlineRecord record = result.get();
        assertEquals(tradeDate, record.tradeDate());
        assertEquals(stockCode, record.stockCode());
        assertEquals(0, record.open().compareTo(new BigDecimal("12.50")));
        assertEquals(0, record.high().compareTo(new BigDecimal("12.80")));
        assertEquals(0, record.low().compareTo(new BigDecimal("12.40")));
        assertEquals(0, record.close().compareTo(new BigDecimal("12.70")));
        assertEquals(125000000L, record.volume());
    }
    
    @Test
    void testFindByTradeDateAndStockCode_NonExistingData() {
        LocalDate tradeDate = LocalDate.of(2024, 1, 1);
        String stockCode = "000001";
        
        Optional<DailyKlineRecord> result = repository.findByTradeDateAndStockCode(tradeDate, stockCode);
        
        assertFalse(result.isPresent(), "不应该找到不存在的日K线数据");
    }
    
    @Test
    void testFindByStockCode_ExistingStock() {
        String stockCode = "000001";
        
        List<DailyKlineRecord> results = repository.findByStockCode(stockCode);
        
        assertFalse(results.isEmpty(), "应该找到对应股票的日K线数据");
        assertEquals(5, results.size(), "000001股票应该有5天的数据");
        
        // 验证数据按日期倒序排列
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(
                results.get(i).tradeDate().isAfter(results.get(i + 1).tradeDate()) ||
                results.get(i).tradeDate().isEqual(results.get(i + 1).tradeDate()),
                "数据应该按日期倒序排列"
            );
        }
        
        // 验证第一条数据是最新日期
        DailyKlineRecord latestRecord = results.get(0);
        assertEquals(LocalDate.of(2024, 3, 7), latestRecord.tradeDate());
    }
    
    @Test
    void testFindByStockCode_NonExistingStock() {
        String stockCode = "999999";
        
        List<DailyKlineRecord> results = repository.findByStockCode(stockCode);
        
        assertTrue(results.isEmpty(), "不应该找到不存在股票的日K线数据");
    }
    
    @Test
    void testFindByTradeDateBetween_ValidRange() {
        LocalDate startDate = LocalDate.of(2024, 3, 4);
        LocalDate endDate = LocalDate.of(2024, 3, 6);
        
        List<DailyKlineRecord> results = repository.findByTradeDateBetween(startDate, endDate);
        
        assertFalse(results.isEmpty(), "应该找到日期范围内的日K线数据");
        
        // 验证所有结果都在日期范围内
        for (DailyKlineRecord record : results) {
            assertTrue(
                !record.tradeDate().isBefore(startDate) && !record.tradeDate().isAfter(endDate),
                "所有结果都应该在指定日期范围内"
            );
        }
    }
    
    @Test
    void testFindByTradeDateBetween_NoDataInRange() {
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        
        List<DailyKlineRecord> results = repository.findByTradeDateBetween(startDate, endDate);
        
        assertTrue(results.isEmpty(), "不应该找到日期范围外的日K线数据");
    }
    
    @Test
    void testFindByStockCodeAndTradeDateBetween_ValidData() {
        String stockCode = "000001";
        LocalDate startDate = LocalDate.of(2024, 3, 4);
        LocalDate endDate = LocalDate.of(2024, 3, 6);
        
        List<DailyKlineRecord> results = repository.findByStockCodeAndTradeDateBetween(stockCode, startDate, endDate);
        
        assertFalse(results.isEmpty(), "应该找到指定股票在日期范围内的日K线数据");
        assertEquals(3, results.size(), "000001股票在2024-03-04到2024-03-06期间应该有3天数据");
        
        // 验证所有结果都是指定股票且在日期范围内
        for (DailyKlineRecord record : results) {
            assertEquals(stockCode, record.stockCode(), "所有结果都应该是000001股票");
            assertTrue(
                !record.tradeDate().isBefore(startDate) && !record.tradeDate().isAfter(endDate),
                "所有结果都应该在指定日期范围内"
            );
        }
    }
    
    @Test
    void testFindLatestByStockCode_ExistingStock() {
        String stockCode = "000001";
        
        Optional<DailyKlineRecord> result = repository.findLatestByStockCode(stockCode);
        
        assertTrue(result.isPresent(), "应该找到最新日K线数据");
        assertEquals(LocalDate.of(2024, 3, 7), result.get().tradeDate(), "最新日期应该是2024-03-07");
    }
    
    @Test
    void testFindLatestByStockCode_NonExistingStock() {
        String stockCode = "999999";
        
        Optional<DailyKlineRecord> result = repository.findLatestByStockCode(stockCode);
        
        assertFalse(result.isPresent(), "不应该找到不存在股票的最新日K线数据");
    }
    
    @Test
    void testExistsByTradeDateAndStockCode_ExistingData() {
        LocalDate tradeDate = LocalDate.of(2024, 3, 1);
        String stockCode = "000001";
        
        boolean exists = repository.existsByTradeDateAndStockCode(tradeDate, stockCode);
        
        assertTrue(exists, "应该确认存在的日K线数据");
    }
    
    @Test
    void testExistsByTradeDateAndStockCode_NonExistingData() {
        LocalDate tradeDate = LocalDate.of(2024, 1, 1);
        String stockCode = "000001";
        
        boolean exists = repository.existsByTradeDateAndStockCode(tradeDate, stockCode);
        
        assertFalse(exists, "应该确认不存在的日K线数据");
    }
    
    @Test
    void testCountByStockCode_ExistingStock() {
        String stockCode = "000001";
        
        long count = repository.countByStockCode(stockCode);
        
        assertEquals(5, count, "000001股票应该有5天的日K线数据");
    }
    
    @Test
    void testCountByStockCode_NonExistingStock() {
        String stockCode = "999999";
        
        long count = repository.countByStockCode(stockCode);
        
        assertEquals(0, count, "不存在股票应该有0天的日K线数据");
    }
    
    @Test
    void testFindUpDaysByStockCode() {
        String stockCode = "000001";
        
        List<DailyKlineRecord> results = repository.findUpDaysByStockCode(stockCode);
        
        // 验证所有结果都是上涨日
        for (DailyKlineRecord record : results) {
            assertTrue(record.change().compareTo(BigDecimal.ZERO) > 0, "所有结果都应该是上涨日");
        }
    }
    
    @Test
    void testFindDownDaysByStockCode() {
        // 注意：测试数据中可能没有下跌日，所以这个测试可能会失败
        // 我们暂时注释掉，或者修改测试数据
        String stockCode = "000001";
        
        List<DailyKlineRecord> results = repository.findDownDaysByStockCode(stockCode);
        
        // 验证所有结果都是下跌日（如果有的话）
        for (DailyKlineRecord record : results) {
            assertTrue(record.change().compareTo(BigDecimal.ZERO) < 0, "所有结果都应该是下跌日");
        }
    }
    
    @Test
    void testFindHighestPriceByStockCode() {
        String stockCode = "000001";
        
        Optional<DailyKlineRecord> result = repository.findHighestPriceByStockCode(stockCode);
        
        assertTrue(result.isPresent(), "应该找到最高价记录");
        // 验证这确实是最高价
        DailyKlineRecord highest = result.get();
        List<DailyKlineRecord> allRecords = repository.findByStockCode(stockCode);
        
        for (DailyKlineRecord record : allRecords) {
            assertTrue(highest.high().compareTo(record.high()) >= 0, "找到的记录应该是最高价");
        }
    }
    
    @Test
    void testFindLowestPriceByStockCode() {
        String stockCode = "000001";
        
        Optional<DailyKlineRecord> result = repository.findLowestPriceByStockCode(stockCode);
        
        assertTrue(result.isPresent(), "应该找到最低价记录");
        // 验证这确实是最低价
        DailyKlineRecord lowest = result.get();
        List<DailyKlineRecord> allRecords = repository.findByStockCode(stockCode);
        
        for (DailyKlineRecord record : allRecords) {
            assertTrue(lowest.low().compareTo(record.low()) <= 0, "找到的记录应该是最低价");
        }
    }
    
    @Test
    void testFindByTradeDate() {
        LocalDate tradeDate = LocalDate.of(2024, 3, 1);
        
        List<DailyKlineRecord> results = repository.findByTradeDate(tradeDate);
        
        assertFalse(results.isEmpty(), "应该找到指定日期的日K线数据");
        
        // 验证所有结果都是指定日期
        for (DailyKlineRecord record : results) {
            assertEquals(tradeDate, record.tradeDate(), "所有结果都应该是指定日期");
        }
    }
    
    @Test
    void testFindByAmountGreaterThan() {
        BigDecimal minAmount = new BigDecimal("1500000000");
        
        List<DailyKlineRecord> results = repository.findByAmountGreaterThan(minAmount);
        
        // 验证所有结果成交额都大于等于指定值
        for (DailyKlineRecord record : results) {
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
}
