package com.quant.strategy.domain.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体类基础功能测试
 */
class EntityTest {
    
    @Test
    void testStockInfoEntity() {
        StockInfo stock = StockInfo.builder()
                .stockCode("600406")
                .shortName("国电南瑞")
                .exchange("SH")
                .marketCap(new BigDecimal("1500.00"))
                .industry("电力设备")
                .isActive(1)
                .build();
        
        assertEquals("600406", stock.getStockCode());
        assertEquals("国电南瑞", stock.getShortName());
        assertEquals("SH", stock.getExchange());
        assertEquals("上海证券交易所", stock.getExchangeName());
        assertTrue(stock.isActiveStock());
        assertEquals("SH.600406", stock.getFullStockCode());
    }
    
    @Test
    void testDailyKlineEntity() {
        DailyKline.DailyKlineId id = DailyKline.DailyKlineId.builder()
                .tradeDate(LocalDate.of(2025, 4, 14))
                .stockCode("600406")
                .build();
        
        DailyKline kline = DailyKline.builder()
                .id(id)
                .preClose(new BigDecimal("25.00"))
                .open(new BigDecimal("25.50"))
                .high(new BigDecimal("26.00"))
                .low(new BigDecimal("25.20"))
                .close(new BigDecimal("25.80"))
                .volume(1000000L)
                .amount(new BigDecimal("2580.00"))
                .change(new BigDecimal("0.80"))
                .changePct(new BigDecimal("3.20"))
                .amplitude(new BigDecimal("3.20"))
                .build();
        
        assertEquals(LocalDate.of(2025, 4, 14), kline.getId().getTradeDate());
        assertEquals("600406", kline.getId().getStockCode());
        assertEquals(new BigDecimal("25.50"), kline.getOpen());
        assertEquals(new BigDecimal("26.00"), kline.getHigh());
        assertTrue(kline.isUpDay());
        assertFalse(kline.isDownDay());
        assertFalse(kline.isFlatDay());
        
        // 测试计算字段
        assertEquals(new BigDecimal("0.80"), kline.getEntitySize());
        assertTrue(kline.getAmountInYuan().compareTo(new BigDecimal("25800000")) == 0);
    }
    
    @Test
    void testMinuteKlineEntity() {
        MinuteKline.MinuteKlineId id = MinuteKline.MinuteKlineId.builder()
                .tradeTime(LocalDateTime.of(2025, 4, 14, 9, 30))
                .stockCode("600406")
                .intervalType(5)
                .build();
        
        MinuteKline kline = MinuteKline.builder()
                .id(id)
                .open(new BigDecimal("25.50"))
                .high(new BigDecimal("25.60"))
                .low(new BigDecimal("25.45"))
                .close(new BigDecimal("25.55"))
                .volume(10000L)
                .amount(new BigDecimal("255.50"))
                .build();
        
        assertEquals(5, kline.getIntervalMinutes());
        assertEquals("5分钟", kline.getIntervalDescription());
        assertEquals(new BigDecimal("0.05"), kline.getEntitySize());
        assertEquals(new BigDecimal("0.15"), kline.getPriceRange());
        assertTrue(kline.isUpKline());
        assertTrue(kline.getAmountInYuan().compareTo(new BigDecimal("2555000")) == 0);
    }
}