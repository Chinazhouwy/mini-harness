package com.quant.strategy.domain.repository.impl;

import com.quant.strategy.domain.record.DailyKlineRecord;
import com.quant.strategy.domain.repository.DailyKlineRecordRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 日K线行情Record Repository实现
 * 由于DailyKlineRecord没有单字段主键，这里使用自定义实现而不是继承RecordJdbcRepository
 */
@Repository
public class DailyKlineRecordRepositoryImpl implements DailyKlineRecordRepository {
    
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private static final String TABLE_NAME = "harness_db.daily_kline";
    
    public DailyKlineRecordRepositoryImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    private final RowMapper<DailyKlineRecord> rowMapper = new RowMapper<DailyKlineRecord>() {
        @Override
        public DailyKlineRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DailyKlineRecord(
                rs.getObject("trade_date", LocalDate.class),
                rs.getString("stock_code"),
                rs.getObject("pre_close", BigDecimal.class),
                rs.getObject("open", BigDecimal.class),
                rs.getObject("high", BigDecimal.class),
                rs.getObject("low", BigDecimal.class),
                rs.getObject("close", BigDecimal.class),
                rs.getObject("volume", Long.class),
                rs.getObject("amount", BigDecimal.class),
                rs.getObject("turnover_rate", BigDecimal.class),
                rs.getObject("change", BigDecimal.class),
                rs.getObject("change_pct", BigDecimal.class),
                rs.getObject("amplitude", BigDecimal.class),
                rs.getObject("update_time", LocalDateTime.class)
            );
        }
    };
    
    private MapSqlParameterSource toParamMap(DailyKlineRecord record) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("trade_date", record.tradeDate());
        params.addValue("stock_code", record.stockCode());
        params.addValue("pre_close", record.preClose());
        params.addValue("open", record.open());
        params.addValue("high", record.high());
        params.addValue("low", record.low());
        params.addValue("close", record.close());
        params.addValue("volume", record.volume());
        params.addValue("amount", record.amount());
        params.addValue("turnover_rate", record.turnoverRate());
        params.addValue("change", record.change());
        params.addValue("change_pct", record.changePct());
        params.addValue("amplitude", record.amplitude());
        params.addValue("update_time", record.updateTime());
        return params;
    }
    
    @Override
    public DailyKlineRecord save(DailyKlineRecord dailyKline) {
        if (existsByTradeDateAndStockCode(dailyKline.tradeDate(), dailyKline.stockCode())) {
            update(dailyKline);
        } else {
            insert(dailyKline);
        }
        return dailyKline;
    }
    
    private void insert(DailyKlineRecord record) {
        String sql = "INSERT INTO " + TABLE_NAME + " (trade_date, stock_code, pre_close, open, high, low, close, volume, amount, turnover_rate, change, change_pct, amplitude, update_time) " +
                    "VALUES (:trade_date, :stock_code, :pre_close, :open, :high, :low, :close, :volume, :amount, :turnover_rate, :change, :change_pct, :amplitude, :update_time)";
        jdbcTemplate.update(sql, toParamMap(record));
    }
    
    private void update(DailyKlineRecord record) {
        String sql = "UPDATE " + TABLE_NAME + " SET pre_close = :pre_close, open = :open, high = :high, low = :low, close = :close, " +
                    "volume = :volume, amount = :amount, turnover_rate = :turnover_rate, change = :change, change_pct = :change_pct, " +
                    "amplitude = :amplitude, update_time = :update_time " +
                    "WHERE trade_date = :trade_date AND stock_code = :stock_code";
        jdbcTemplate.update(sql, toParamMap(record));
    }
    
    @Override
    public List<DailyKlineRecord> saveAll(List<DailyKlineRecord> dailyKlines) {
        List<DailyKlineRecord> saved = new ArrayList<>();
        for (DailyKlineRecord record : dailyKlines) {
            saved.add(save(record));
        }
        return saved;
    }
    
    @Override
    public Optional<DailyKlineRecord> findByTradeDateAndStockCode(LocalDate tradeDate, String stockCode) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE trade_date = :trade_date AND stock_code = :stock_code";
        Map<String, Object> params = Map.of("trade_date", tradeDate, "stock_code", stockCode);
        
        List<DailyKlineRecord> results = jdbcTemplate.query(sql, params, rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    @Override
    public List<DailyKlineRecord> findByStockCode(String stockCode) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE stock_code = :stock_code ORDER BY trade_date DESC";
        return jdbcTemplate.query(sql, Map.of("stock_code", stockCode), rowMapper);
    }
    
    @Override
    public List<DailyKlineRecord> findByTradeDateBetween(LocalDate startDate, LocalDate endDate) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE trade_date BETWEEN :start_date AND :end_date ORDER BY trade_date DESC";
        Map<String, Object> params = Map.of("start_date", startDate, "end_date", endDate);
        return jdbcTemplate.query(sql, params, rowMapper);
    }
    
    @Override
    public List<DailyKlineRecord> findByStockCodeAndTradeDateBetween(String stockCode, LocalDate startDate, LocalDate endDate) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE stock_code = :stock_code AND trade_date BETWEEN :start_date AND :end_date ORDER BY trade_date DESC";
        Map<String, Object> params = Map.of("stock_code", stockCode, "start_date", startDate, "end_date", endDate);
        return jdbcTemplate.query(sql, params, rowMapper);
    }
    
    @Override
    public Optional<DailyKlineRecord> findLatestByStockCode(String stockCode) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE stock_code = :stock_code ORDER BY trade_date DESC LIMIT 1";
        List<DailyKlineRecord> results = jdbcTemplate.query(sql, Map.of("stock_code", stockCode), rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    @Override
    public void deleteByTradeDateAndStockCode(LocalDate tradeDate, String stockCode) {
        String sql = "DELETE FROM " + TABLE_NAME + " WHERE trade_date = :trade_date AND stock_code = :stock_code";
        jdbcTemplate.update(sql, Map.of("trade_date", tradeDate, "stock_code", stockCode));
    }
    
    @Override
    public void deleteAllByStockCode(String stockCode) {
        String sql = "DELETE FROM " + TABLE_NAME + " WHERE stock_code = :stock_code";
        jdbcTemplate.update(sql, Map.of("stock_code", stockCode));
    }
    
    @Override
    public boolean existsByTradeDateAndStockCode(LocalDate tradeDate, String stockCode) {
        String sql = "SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE trade_date = :trade_date AND stock_code = :stock_code";
        Long count = jdbcTemplate.queryForObject(sql, Map.of("trade_date", tradeDate, "stock_code", stockCode), Long.class);
        return count != null && count > 0;
    }
    
    @Override
    public long countByStockCode(String stockCode) {
        String sql = "SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE stock_code = :stock_code";
        Long count = jdbcTemplate.queryForObject(sql, Map.of("stock_code", stockCode), Long.class);
        return count != null ? count : 0;
    }
    
    @Override
    public List<DailyKlineRecord> findUpDaysByStockCode(String stockCode) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE stock_code = :stock_code AND change > 0 ORDER BY trade_date DESC";
        return jdbcTemplate.query(sql, Map.of("stock_code", stockCode), rowMapper);
    }
    
    @Override
    public List<DailyKlineRecord> findDownDaysByStockCode(String stockCode) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE stock_code = :stock_code AND change < 0 ORDER BY trade_date DESC";
        return jdbcTemplate.query(sql, Map.of("stock_code", stockCode), rowMapper);
    }
    
    @Override
    public Optional<DailyKlineRecord> findHighestPriceByStockCode(String stockCode) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE stock_code = :stock_code ORDER BY high DESC LIMIT 1";
        List<DailyKlineRecord> results = jdbcTemplate.query(sql, Map.of("stock_code", stockCode), rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    @Override
    public Optional<DailyKlineRecord> findLowestPriceByStockCode(String stockCode) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE stock_code = :stock_code ORDER BY low ASC LIMIT 1";
        List<DailyKlineRecord> results = jdbcTemplate.query(sql, Map.of("stock_code", stockCode), rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    @Override
    public List<DailyKlineRecord> findLatestByExchange(String exchange) {
        String sql = "SELECT dk.* FROM " + TABLE_NAME + " dk " +
                    "JOIN harness_db.stock_info si ON dk.stock_code = si.stock_code " +
                    "WHERE si.exchange = :exchange " +
                    "AND dk.trade_date = (SELECT MAX(trade_date) FROM " + TABLE_NAME + " WHERE stock_code = dk.stock_code)";
        return jdbcTemplate.query(sql, Map.of("exchange", exchange), rowMapper);
    }
    
    @Override
    public List<DailyKlineRecord> findByTradeDate(LocalDate tradeDate) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE trade_date = :trade_date";
        return jdbcTemplate.query(sql, Map.of("trade_date", tradeDate), rowMapper);
    }
    
    @Override
    public List<DailyKlineRecord> findByAmountGreaterThan(BigDecimal minAmount) {
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE amount >= :min_amount ORDER BY amount DESC";
        return jdbcTemplate.query(sql, Map.of("min_amount", minAmount), rowMapper);
    }
}