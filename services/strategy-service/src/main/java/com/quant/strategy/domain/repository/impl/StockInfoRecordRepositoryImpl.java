package com.quant.strategy.domain.repository.impl;

import com.quant.strategy.domain.record.StockInfoRecord;
import com.quant.strategy.domain.repository.StockInfoRecordRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 股票基本信息Record Repository实现
 * 只包含查询操作，不包含增删改操作
 */
@Repository
public class StockInfoRecordRepositoryImpl implements StockInfoRecordRepository {
    
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private static final String TABLE_NAME = "harness_db.stock_info";
    
    public StockInfoRecordRepositoryImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    private final RowMapper<StockInfoRecord> rowMapper = new RowMapper<StockInfoRecord>() {
        @Override
        public StockInfoRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new StockInfoRecord(
                rs.getString("stock_code"),
                rs.getString("short_name"),
                rs.getString("full_name"),
                rs.getString("exchange"),
                rs.getObject("market_cap", BigDecimal.class),
                rs.getString("industry"),
                rs.getString("region"),
                rs.getObject("listing_date", LocalDate.class),
                rs.getObject("is_active", Integer.class),
                rs.getObject("update_time", LocalDateTime.class)
            );
        }
    };
    
    @Override
    public Optional<StockInfoRecord> findByStockCode(String stockCode) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE stock_code = :stock_code";
        List<StockInfoRecord> results = jdbcTemplate.query(sql, 
                Map.of("stock_code", stockCode), 
                rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    @Override
    public List<StockInfoRecord> findAll() {
        String sql = "SELECT * FROM " + TABLE_NAME;
        return jdbcTemplate.query(sql, rowMapper);
    }
    
    @Override
    public List<StockInfoRecord> findByExchange(String exchange) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE exchange = :exchange";
        return jdbcTemplate.query(sql, 
                Map.of("exchange", exchange),
                rowMapper);
    }
    
    @Override
    public List<StockInfoRecord> findByIndustry(String industry) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE industry = :industry";
        return jdbcTemplate.query(sql, 
                Map.of("industry", industry),
                rowMapper);
    }
    
    @Override
    public boolean existsByStockCode(String stockCode) {
        String sql = "SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE stock_code = :stock_code";
        Long count = jdbcTemplate.queryForObject(sql, 
                Map.of("stock_code", stockCode), 
                Long.class);
        return count != null && count > 0;
    }
    
    @Override
    public long count() {
        String sql = "SELECT COUNT(*) FROM " + TABLE_NAME;
        Long count = jdbcTemplate.queryForObject(sql, Map.of(), Long.class);
        return count != null ? count : 0;
    }
    
    @Override
    public List<StockInfoRecord> findActiveStocks() {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE is_active = 1";
        return jdbcTemplate.query(sql, rowMapper);
    }
    
    @Override
    public List<StockInfoRecord> findByStockCodes(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return List.of();
        }
        
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE stock_code IN (:stockCodes)";
        return jdbcTemplate.query(sql, 
                Map.of("stockCodes", stockCodes),
                rowMapper);
    }
    
    @Override
    public List<StockInfoRecord> findActiveStocksByExchange(String exchange) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE exchange = :exchange AND is_active = 1";
        return jdbcTemplate.query(sql, 
                Map.of("exchange", exchange),
                rowMapper);
    }
    
    @Override
    public List<StockInfoRecord> findStocksByRegion(String region) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE region = :region";
        return jdbcTemplate.query(sql, 
                Map.of("region", region),
                rowMapper);
    }
    
    @Override
    public List<StockInfoRecord> findStocksByCapRange(BigDecimal minCap, BigDecimal maxCap) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE market_cap >= :minCap AND market_cap <= :maxCap";
        return jdbcTemplate.query(sql, 
                Map.of("minCap", minCap, "maxCap", maxCap),
                rowMapper);
    }
}