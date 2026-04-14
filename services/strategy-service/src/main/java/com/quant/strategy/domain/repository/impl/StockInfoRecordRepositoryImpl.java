package com.quant.strategy.domain.repository.impl;

import com.quant.strategy.domain.record.StockInfoRecord;
import com.quant.strategy.domain.repository.RecordJdbcRepository;
import com.quant.strategy.domain.repository.StockInfoRecordRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 股票基本信息Record Repository实现
 */
@Repository
public class StockInfoRecordRepositoryImpl extends RecordJdbcRepository<StockInfoRecord, String> 
        implements StockInfoRecordRepository {
    
    public StockInfoRecordRepositoryImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate, "stock_info", "harness_db");
    }
    
    @Override
    protected boolean isNew(StockInfoRecord record) {
        // 对于stock_info表，主键是stockCode，如果stockCode为空则为新增
        return record.stockCode() == null || record.stockCode().isEmpty();
    }
    
    @Override
    protected String getId(StockInfoRecord record) {
        return record.stockCode();
    }
    
    @Override
    protected StockInfoRecord setGeneratedId(StockInfoRecord record, KeyHolder keyHolder) {
        // stock_info表的主键是业务主键（stockCode），不是自增ID
        // 所以不需要设置生成的主键，返回null表示使用原record
        return null;
    }
    
    @Override
    protected String getIdColumnName() {
        return "stock_code";
    }
    
    @Override
    public Optional<StockInfoRecord> findByStockCode(String stockCode) {
        return findById(stockCode);
    }
    
    @Override
    public List<StockInfoRecord> findByExchange(String exchange) {
        String sql = String.format("SELECT * FROM %s WHERE exchange = :exchange", getFullTableName());
        return jdbcTemplate.query(sql, 
                java.util.Map.of("exchange", exchange),
                createRowMapper());
    }
    
    @Override
    public List<StockInfoRecord> findByIndustry(String industry) {
        String sql = String.format("SELECT * FROM %s WHERE industry = :industry", getFullTableName());
        return jdbcTemplate.query(sql, 
                java.util.Map.of("industry", industry),
                createRowMapper());
    }
    
    @Override
    public void deleteByStockCode(String stockCode) {
        deleteById(stockCode);
    }
    
    @Override
    public List<StockInfoRecord> findActiveStocks() {
        String sql = String.format("SELECT * FROM %s WHERE is_active = 1", getFullTableName());
        return jdbcTemplate.query(sql, createRowMapper());
    }
    
    @Override
    public List<StockInfoRecord> findStocksByRegion(String region) {
        String sql = String.format("SELECT * FROM %s WHERE region = :region", getFullTableName());
        return jdbcTemplate.query(sql, 
                java.util.Map.of("region", region),
                createRowMapper());
    }
    
    @Override
    public List<StockInfoRecord> findStocksByCapRange(BigDecimal minCap, BigDecimal maxCap) {
        String sql = String.format("SELECT * FROM %s WHERE market_cap >= :minCap AND market_cap <= :maxCap", getFullTableName());
        return jdbcTemplate.query(sql, 
                java.util.Map.of("minCap", minCap, "maxCap", maxCap),
                createRowMapper());
    }
    
    @Override
    public boolean existsByStockCode(String stockCode) {
        return existsById(stockCode);
    }
    
    @Override
    public List<StockInfoRecord> findByStockCodes(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return List.of();
        }
        
        String sql = String.format("SELECT * FROM %s WHERE stock_code IN (:stockCodes)", getFullTableName());
        return jdbcTemplate.query(sql, 
                java.util.Map.of("stockCodes", stockCodes),
                createRowMapper());
    }
    
    @Override
    public void updateMarketCap(String stockCode, BigDecimal marketCap) {
        String sql = String.format("UPDATE %s SET market_cap = :marketCap WHERE stock_code = :stockCode", getFullTableName());
        jdbcTemplate.update(sql, 
                java.util.Map.of("stockCode", stockCode, "marketCap", marketCap));
    }
    
    @Override
    public List<StockInfoRecord> findActiveStocksByExchange(String exchange) {
        String sql = String.format("SELECT * FROM %s WHERE exchange = :exchange AND is_active = 1", getFullTableName());
        return jdbcTemplate.query(sql, 
                java.util.Map.of("exchange", exchange),
                createRowMapper());
    }
}