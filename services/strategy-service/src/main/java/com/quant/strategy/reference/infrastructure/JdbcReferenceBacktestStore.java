package com.quant.strategy.reference.infrastructure;

import com.quant.strategy.reference.application.ReferenceBacktestRequest;
import com.quant.strategy.reference.domain.BacktestMetrics;
import com.quant.strategy.reference.domain.BacktestTaskStatus;
import com.quant.strategy.reference.domain.EquityPoint;
import com.quant.strategy.reference.domain.OrderSide;
import com.quant.strategy.reference.domain.ReferenceBacktestReport;
import com.quant.strategy.reference.domain.ReferenceBacktestTask;
import com.quant.strategy.reference.domain.SignalType;
import com.quant.strategy.reference.domain.SimulatedOrder;
import com.quant.strategy.reference.domain.TradingSignal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
 * Reference 回测任务 PostgreSQL 实现。
 *
 * <p>这是 Phase 2 的关键替换点：业务代码仍然依赖 {@link ReferenceBacktestStore}，
 * 只是把内存 Map 换成 PostgreSQL 表。为了保持可读性，这里先使用 JdbcTemplate
 * 明确写 SQL，而不是引入 ORM。</p>
 */
@Repository
@ConditionalOnProperty(name = "reference.store.type", havingValue = "jdbc")
public class JdbcReferenceBacktestStore implements ReferenceBacktestStore {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcReferenceBacktestStore(
        @Qualifier("referenceNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(ReferenceBacktestTask task) {
        deleteExistingDetails(task.taskId());
        insertTask(task);
        if (task.report() != null) {
            insertSignals(task.taskId(), task.report().signals());
            insertOrders(task.taskId(), task.report().orders());
            insertEquityCurve(task.taskId(), task.report().equityCurve());
        }
    }

    @Override
    public Optional<ReferenceBacktestTask> findById(String taskId) {
        List<ReferenceBacktestTask> tasks = jdbcTemplate.query(
            "SELECT * FROM backtest_task WHERE task_id = :taskId",
            Map.of("taskId", taskId),
            taskMapper()
        );
        return tasks.isEmpty() ? Optional.empty() : Optional.of(tasks.get(0));
    }

    @Override
    public Optional<ReferenceBacktestTask> latestSuccessfulTask() {
        List<ReferenceBacktestTask> tasks = jdbcTemplate.query(
            "SELECT * FROM backtest_task WHERE status = 'SUCCESS' ORDER BY created_at DESC LIMIT 1",
            Map.of(),
            taskMapper()
        );
        return tasks.isEmpty() ? Optional.empty() : Optional.of(tasks.get(0));
    }

    @Override
    public List<ReferenceBacktestTask> findRecent(int limit) {
        return jdbcTemplate.query(
            "SELECT * FROM backtest_task ORDER BY created_at DESC LIMIT :limit",
            Map.of("limit", Math.max(1, limit)),
            taskMapper()
        );
    }

    @Override
    public List<ReferenceBacktestTask> findPage(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        return jdbcTemplate.query(
            "SELECT * FROM backtest_task ORDER BY created_at DESC LIMIT :size OFFSET :offset",
            Map.of("size", safeSize, "offset", safePage * safeSize),
            taskMapper()
        );
    }

    @Override
    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_task", Map.of(), Long.class);
        return count == null ? 0L : count;
    }

    private void deleteExistingDetails(String taskId) {
        Map<String, Object> params = Map.of("taskId", taskId);
        jdbcTemplate.update("DELETE FROM equity_curve_point WHERE task_id = :taskId", params);
        jdbcTemplate.update("DELETE FROM simulated_order WHERE task_id = :taskId", params);
        jdbcTemplate.update("DELETE FROM backtest_signal WHERE task_id = :taskId", params);
        jdbcTemplate.update("DELETE FROM backtest_task WHERE task_id = :taskId", params);
    }

    private void insertTask(ReferenceBacktestTask task) {
        ReferenceBacktestRequest request = task.request();
        ReferenceBacktestReport report = task.report();
        BacktestMetrics metrics = report == null ? null : report.metrics();

        jdbcTemplate.update(
            """
            INSERT INTO backtest_task (
                task_id, stock_code, start_date, end_date, fast_window, slow_window,
                initial_cash, max_position_ratio, stop_loss_ratio, status, error_message,
                final_equity, total_return, max_drawdown, trade_count, winning_trades, win_rate, created_at
            ) VALUES (
                :taskId, :stockCode, :startDate, :endDate, :fastWindow, :slowWindow,
                :initialCash, :maxPositionRatio, :stopLossRatio, :status, :errorMessage,
                :finalEquity, :totalReturn, :maxDrawdown, :tradeCount, :winningTrades, :winRate, :createdAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("taskId", task.taskId())
                .addValue("stockCode", request.stockCode())
                .addValue("startDate", request.startDate())
                .addValue("endDate", request.endDate())
                .addValue("fastWindow", request.fastWindow())
                .addValue("slowWindow", request.slowWindow())
                .addValue("initialCash", request.initialCash())
                .addValue("maxPositionRatio", request.maxPositionRatio())
                .addValue("stopLossRatio", request.stopLossRatio())
                .addValue("status", task.status().name())
                .addValue("errorMessage", task.errorMessage())
                .addValue("finalEquity", metrics == null ? null : metrics.finalEquity())
                .addValue("totalReturn", metrics == null ? null : metrics.totalReturn())
                .addValue("maxDrawdown", metrics == null ? null : metrics.maxDrawdown())
                .addValue("tradeCount", metrics == null ? null : metrics.tradeCount())
                .addValue("winningTrades", metrics == null ? null : metrics.winningTrades())
                .addValue("winRate", metrics == null ? null : metrics.winRate())
                .addValue("createdAt", task.createdAt())
        );
    }

    private void insertSignals(String taskId, List<TradingSignal> signals) {
        for (TradingSignal signal : signals) {
            jdbcTemplate.update(
                """
                INSERT INTO backtest_signal (
                    task_id, trade_date, stock_code, signal_type, price, fast_average, slow_average, reason
                ) VALUES (
                    :taskId, :tradeDate, :stockCode, :signalType, :price, :fastAverage, :slowAverage, :reason
                )
                """,
                new MapSqlParameterSource()
                    .addValue("taskId", taskId)
                    .addValue("tradeDate", signal.tradeDate())
                    .addValue("stockCode", signal.stockCode())
                    .addValue("signalType", signal.type().name())
                    .addValue("price", signal.price())
                    .addValue("fastAverage", signal.fastAverage())
                    .addValue("slowAverage", signal.slowAverage())
                    .addValue("reason", signal.reason())
            );
        }
    }

    private void insertOrders(String taskId, List<SimulatedOrder> orders) {
        for (SimulatedOrder order : orders) {
            jdbcTemplate.update(
                """
                INSERT INTO simulated_order (
                    order_id, task_id, trade_date, stock_code, side, price, quantity, amount, risk_reason
                ) VALUES (
                    :orderId, :taskId, :tradeDate, :stockCode, :side, :price, :quantity, :amount, :riskReason
                )
                """,
                new MapSqlParameterSource()
                    .addValue("orderId", order.orderId())
                    .addValue("taskId", taskId)
                    .addValue("tradeDate", order.tradeDate())
                    .addValue("stockCode", order.stockCode())
                    .addValue("side", order.side().name())
                    .addValue("price", order.price())
                    .addValue("quantity", order.quantity())
                    .addValue("amount", order.amount())
                    .addValue("riskReason", order.riskReason())
            );
        }
    }

    private void insertEquityCurve(String taskId, List<EquityPoint> equityCurve) {
        for (EquityPoint point : equityCurve) {
            jdbcTemplate.update(
                """
                INSERT INTO equity_curve_point (
                    task_id, trade_date, cash, position_quantity, close_price, total_equity, drawdown
                ) VALUES (
                    :taskId, :tradeDate, :cash, :positionQuantity, :closePrice, :totalEquity, :drawdown
                )
                """,
                new MapSqlParameterSource()
                    .addValue("taskId", taskId)
                    .addValue("tradeDate", point.tradeDate())
                    .addValue("cash", point.cash())
                    .addValue("positionQuantity", point.positionQuantity())
                    .addValue("closePrice", point.closePrice())
                    .addValue("totalEquity", point.totalEquity())
                    .addValue("drawdown", point.drawdown())
            );
        }
    }

    private RowMapper<ReferenceBacktestTask> taskMapper() {
        return (rs, rowNum) -> {
            String taskId = rs.getString("task_id");
            ReferenceBacktestRequest request = new ReferenceBacktestRequest(
                rs.getString("stock_code"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getInt("fast_window"),
                rs.getInt("slow_window"),
                rs.getBigDecimal("initial_cash"),
                rs.getBigDecimal("max_position_ratio"),
                rs.getBigDecimal("stop_loss_ratio")
            );

            BacktestTaskStatus status = BacktestTaskStatus.valueOf(rs.getString("status"));
            ReferenceBacktestReport report = status == BacktestTaskStatus.SUCCESS
                ? loadReport(taskId, request, rs)
                : null;

            return new ReferenceBacktestTask(
                taskId,
                status,
                request,
                report,
                rs.getString("error_message"),
                rs.getObject("created_at", LocalDateTime.class)
            );
        };
    }

    private ReferenceBacktestReport loadReport(String taskId, ReferenceBacktestRequest request, ResultSet taskRow)
        throws SQLException {
        BacktestMetrics metrics = new BacktestMetrics(
            taskRow.getBigDecimal("initial_cash"),
            taskRow.getBigDecimal("final_equity"),
            taskRow.getBigDecimal("total_return"),
            taskRow.getBigDecimal("max_drawdown"),
            taskRow.getInt("trade_count"),
            taskRow.getInt("winning_trades"),
            taskRow.getBigDecimal("win_rate")
        );

        return new ReferenceBacktestReport(
            request.stockCode(),
            request.startDate(),
            request.endDate(),
            request.fastWindow(),
            request.slowWindow(),
            metrics,
            loadSignals(taskId),
            loadOrders(taskId),
            loadEquityCurve(taskId),
            List.of()
        );
    }

    private List<TradingSignal> loadSignals(String taskId) {
        return jdbcTemplate.query(
            "SELECT * FROM backtest_signal WHERE task_id = :taskId ORDER BY trade_date ASC, id ASC",
            Map.of("taskId", taskId),
            (rs, rowNum) -> new TradingSignal(
                rs.getObject("trade_date", LocalDate.class),
                rs.getString("stock_code"),
                SignalType.valueOf(rs.getString("signal_type")),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("fast_average"),
                rs.getBigDecimal("slow_average"),
                rs.getString("reason")
            )
        );
    }

    private List<SimulatedOrder> loadOrders(String taskId) {
        return jdbcTemplate.query(
            "SELECT * FROM simulated_order WHERE task_id = :taskId ORDER BY trade_date ASC, order_id ASC",
            Map.of("taskId", taskId),
            (rs, rowNum) -> new SimulatedOrder(
                rs.getString("order_id"),
                rs.getObject("trade_date", LocalDate.class),
                rs.getString("stock_code"),
                OrderSide.valueOf(rs.getString("side")),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("amount"),
                rs.getString("risk_reason")
            )
        );
    }

    private List<EquityPoint> loadEquityCurve(String taskId) {
        return jdbcTemplate.query(
            "SELECT * FROM equity_curve_point WHERE task_id = :taskId ORDER BY trade_date ASC, id ASC",
            Map.of("taskId", taskId),
            (rs, rowNum) -> new EquityPoint(
                rs.getObject("trade_date", LocalDate.class),
                rs.getBigDecimal("cash"),
                rs.getBigDecimal("position_quantity"),
                rs.getBigDecimal("close_price"),
                rs.getBigDecimal("total_equity"),
                rs.getBigDecimal("drawdown")
            )
        );
    }
}
