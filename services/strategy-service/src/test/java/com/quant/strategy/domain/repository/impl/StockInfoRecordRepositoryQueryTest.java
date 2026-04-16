package com.quant.strategy.domain.repository.impl;

import com.quant.strategy.domain.record.StockInfoRecord;
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
 * StockInfoRecordRepository 查询操作测试
 * 使用事务回滚确保测试数据不会污染数据库
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Transactional
@Sql(scripts = {
    "/schema-test.sql",
    "/data-stock-info-test.sql"
}, config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
class StockInfoRecordRepositoryQueryTest extends AbstractClickHouseIntegrationTest {
    
    @Autowired
    private StockInfoRecordRepositoryImpl repository;
    
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;
    
    @BeforeEach
    void setUp() {
        // 验证测试数据已正确加载
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM harness_db.stock_info", java.util.Map.of(), Long.class);
        assertNotNull(count, "测试数据应该已加载");
    }
    
    @Test
    void testFindByStockCode_ExistingStock() {
        Optional<StockInfoRecord> result = repository.findByStockCode("600406");
        
        assertTrue(result.isPresent(), "应该找到股票代码为600406的股票");
        StockInfoRecord stock = result.get();
        assertEquals("600406", stock.stockCode());
        assertEquals("平安银行", stock.shortName());
        assertEquals("SZ", stock.exchange());
    }
    
    @Test
    void testFindByStockCode_NonExistingStock() {
        Optional<StockInfoRecord> result = repository.findByStockCode("999999");
        
        assertFalse(result.isPresent(), "不应该找到不存在的股票代码");
    }
    
    @Test
    void testFindAll() {
        List<StockInfoRecord> stocks = repository.findAll();
        
        assertFalse(stocks.isEmpty(), "应该找到测试数据中的股票");
        assertTrue(stocks.size() >= 3, "至少应该有3条测试数据");
        
        // 验证数据正确性
        boolean found600406 = stocks.stream().anyMatch(stock -> "600406".equals(stock.stockCode()));
        assertTrue(found600406, "应该包含股票代码600406");
    }
    
    @Test
    void testFindByExchange() {
        List<StockInfoRecord> szStocks = repository.findByExchange("SZ");
        
        assertFalse(szStocks.isEmpty(), "应该找到深圳交易所的股票");
        assertTrue(szStocks.stream().allMatch(stock -> "SZ".equals(stock.exchange())));
        
        List<StockInfoRecord> shStocks = repository.findByExchange("SH");
        assertFalse(shStocks.isEmpty(), "应该找到上海交易所的股票");
        assertTrue(shStocks.stream().allMatch(stock -> "SH".equals(stock.exchange())));
    }
    
    @Test
    void testFindByIndustry() {
        List<StockInfoRecord> bankStocks = repository.findByIndustry("金融业-银行");
        
        assertFalse(bankStocks.isEmpty(), "应该找到金融业-银行的股票");
        assertTrue(bankStocks.stream().allMatch(stock -> "金融业-银行".equals(stock.industry())));
    }
    
    @Test
    void testExistsByStockCode_Existing() {
        boolean exists = repository.existsByStockCode("600406");
        
        assertTrue(exists, "股票代码600406应该存在");
    }
    
    @Test
    void testExistsByStockCode_NonExisting() {
        boolean exists = repository.existsByStockCode("999999");
        
        assertFalse(exists, "股票代码999999不应该存在");
    }
    
    @Test
    void testCount() {
        long count = repository.count();
        
        assertTrue(count >= 3, "至少应该有3条测试数据");
    }
    
    @Test
    void testFindActiveStocks() {
        List<StockInfoRecord> activeStocks = repository.findActiveStocks();
        
        assertFalse(activeStocks.isEmpty(), "应该找到活跃股票");
        assertTrue(activeStocks.stream().allMatch(stock -> stock.isActive() == 1));
    }
    
    @Test
    void testFindByStockCodes() {
        List<StockInfoRecord> stocks = repository.findByStockCodes(List.of("600406", "000001", "600000"));
        
        assertEquals(3, stocks.size(), "应该找到3个指定的股票");
        assertTrue(stocks.stream().allMatch(stock -> 
            stock.stockCode().equals("600406") || 
            stock.stockCode().equals("000001") || 
            stock.stockCode().equals("600000")));
    }
    
    @Test
    void testFindActiveStocksByExchange() {
        List<StockInfoRecord> szActiveStocks = repository.findActiveStocksByExchange("SZ");
        
        assertFalse(szActiveStocks.isEmpty(), "应该找到深圳交易所的活跃股票");
        assertTrue(szActiveStocks.stream().allMatch(stock -> 
            "SZ".equals(stock.exchange()) && stock.isActive() == 1));
    }
    
    @Test
    void testFindStocksByRegion() {
        List<StockInfoRecord> guangdongStocks = repository.findStocksByRegion("广东");
        
        assertFalse(guangdongStocks.isEmpty(), "应该找到广东地区的股票");
        assertTrue(guangdongStocks.stream().allMatch(stock -> "广东".equals(stock.region())));
    }
    
    @Test
    void testFindStocksByCapRange() {
        // 测试数据中应该有市值在3000-4000亿之间的股票
        List<StockInfoRecord> stocks = repository.findStocksByCapRange(
            new BigDecimal("3000.00"),
            new BigDecimal("4000.00")
        );
        
        assertFalse(stocks.isEmpty(), "应该找到市值在3000-4000亿之间的股票");
        assertTrue(stocks.stream().allMatch(stock -> 
            stock.marketCap() != null &&
            stock.marketCap().compareTo(new BigDecimal("3000.00")) >= 0 &&
            stock.marketCap().compareTo(new BigDecimal("4000.00")) <= 0));
    }
    
    @Test
    void testRecordMethods() {
        Optional<StockInfoRecord> stockOpt = repository.findByStockCode("600406");
        assertTrue(stockOpt.isPresent());
        
        StockInfoRecord stock = stockOpt.get();
        
        // 测试record的辅助方法
        assertEquals("深圳证券交易所", stock.getExchangeName());
        assertTrue(stock.isActiveStock());
        assertEquals("SZ.600406", stock.getFullStockCode());
    }
}
