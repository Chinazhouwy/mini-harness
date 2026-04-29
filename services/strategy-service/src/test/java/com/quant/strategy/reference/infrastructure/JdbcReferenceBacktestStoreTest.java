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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcReferenceBacktestStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("harness_db")
        .withUsername("harness")
        .withPassword("harness123");

    private static JdbcReferenceBacktestStore store;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        new ResourceDatabasePopulator(new ClassPathResource("reference-postgres-schema.sql")).execute(dataSource);
        store = new JdbcReferenceBacktestStore(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void saveAndLoadSuccessfulTaskWithDetails() {
        ReferenceBacktestTask task = sampleTask("task-001");

        store.save(task);

        ReferenceBacktestTask loaded = store.findById("task-001").orElseThrow();
        assertEquals(BacktestTaskStatus.SUCCESS, loaded.status());
        assertEquals("000001", loaded.request().stockCode());
        assertEquals(1, loaded.report().signals().size());
        assertEquals(1, loaded.report().orders().size());
        assertEquals(2, loaded.report().equityCurve().size());
        assertEquals(0, loaded.report().metrics().totalReturn().compareTo(new BigDecimal("0.1000")));
        assertTrue(store.latestSuccessfulTask().isPresent());
        assertEquals(1, store.findRecent(5).size());
    }

    @Test
    void saveFailedTaskWithoutReport() {
        ReferenceBacktestRequest request = new ReferenceBacktestRequest(
            "000001",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 31),
            5,
            20,
            new BigDecimal("100000.00"),
            new BigDecimal("0.80"),
            new BigDecimal("0.08")
        );
        ReferenceBacktestTask task = ReferenceBacktestTask.failed(
            "task-failed",
            request,
            "not enough bars",
            LocalDateTime.of(2024, 1, 31, 10, 0)
        );

        store.save(task);

        ReferenceBacktestTask loaded = store.findById("task-failed").orElseThrow();
        assertEquals(BacktestTaskStatus.FAILED, loaded.status());
        assertEquals("not enough bars", loaded.errorMessage());
        assertEquals(null, loaded.report());
    }

    private static ReferenceBacktestTask sampleTask(String taskId) {
        ReferenceBacktestRequest request = new ReferenceBacktestRequest(
            "000001",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 31),
            5,
            20,
            new BigDecimal("100000.00"),
            new BigDecimal("0.80"),
            new BigDecimal("0.08")
        );

        ReferenceBacktestReport report = new ReferenceBacktestReport(
            "000001",
            request.startDate(),
            request.endDate(),
            request.fastWindow(),
            request.slowWindow(),
            new BacktestMetrics(
                new BigDecimal("100000.0000"),
                new BigDecimal("110000.0000"),
                new BigDecimal("0.1000"),
                new BigDecimal("0.0500"),
                1,
                1,
                new BigDecimal("1.0000")
            ),
            List.of(new TradingSignal(
                LocalDate.of(2024, 1, 10),
                "000001",
                SignalType.BUY,
                new BigDecimal("10.0000"),
                new BigDecimal("9.8000"),
                new BigDecimal("9.5000"),
                "golden cross"
            )),
            List.of(new SimulatedOrder(
                "order-001",
                LocalDate.of(2024, 1, 10),
                "000001",
                OrderSide.BUY,
                new BigDecimal("10.0000"),
                new BigDecimal("8000.0000"),
                new BigDecimal("80000.0000"),
                "position ratio within limit"
            )),
            List.of(
                new EquityPoint(
                    LocalDate.of(2024, 1, 10),
                    new BigDecimal("20000.0000"),
                    new BigDecimal("8000.0000"),
                    new BigDecimal("10.0000"),
                    new BigDecimal("100000.0000"),
                    new BigDecimal("0.0000")
                ),
                new EquityPoint(
                    LocalDate.of(2024, 1, 31),
                    new BigDecimal("20000.0000"),
                    new BigDecimal("8000.0000"),
                    new BigDecimal("11.2500"),
                    new BigDecimal("110000.0000"),
                    new BigDecimal("0.0000")
                )
            ),
            List.of()
        );

        return ReferenceBacktestTask.success(taskId, request, report, LocalDateTime.of(2024, 1, 31, 10, 0));
    }
}
