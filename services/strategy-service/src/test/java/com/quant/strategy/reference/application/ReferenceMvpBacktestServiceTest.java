package com.quant.strategy.reference.application;

import com.quant.strategy.domain.record.DailyKlineRecord;
import com.quant.strategy.domain.repository.DailyKlineRecordRepository;
import com.quant.strategy.reference.domain.OrderSide;
import com.quant.strategy.reference.domain.ReferenceQualityReport;
import com.quant.strategy.reference.domain.ReferenceRunResponse;
import com.quant.strategy.reference.domain.ReferenceBacktestReport;
import com.quant.strategy.reference.domain.SignalType;
import com.quant.strategy.reference.infrastructure.InMemoryReferenceBacktestStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceMvpBacktestServiceTest {

    @Test
    void runGeneratesSignalsOrdersAndMetrics() {
        ReferenceMvpBacktestService service = new ReferenceMvpBacktestService(new FakeDailyKlineRepository());

        ReferenceBacktestReport report = service.run(new ReferenceBacktestRequest(
            "000001",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 7),
            2,
            3,
            new BigDecimal("100000.00"),
            new BigDecimal("0.80"),
            new BigDecimal("0.08")
        ));

        assertEquals("000001", report.stockCode());
        assertEquals(2, report.signals().size());
        assertEquals(SignalType.BUY, report.signals().get(0).type());
        assertEquals(SignalType.SELL, report.signals().get(1).type());
        assertEquals(2, report.orders().size());
        assertEquals(OrderSide.BUY, report.orders().get(0).side());
        assertEquals(OrderSide.SELL, report.orders().get(1).side());
        assertEquals(1, report.metrics().tradeCount());
        assertFalse(report.equityCurve().isEmpty());
        assertTrue(report.metrics().maxDrawdown().compareTo(BigDecimal.ZERO) >= 0);
    }

    @Test
    void runRejectsInvalidWindows() {
        ReferenceMvpBacktestService service = new ReferenceMvpBacktestService(new FakeDailyKlineRepository());

        ReferenceBacktestRequest request = new ReferenceBacktestRequest(
            "000001",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 7),
            5,
            5,
            null,
            null,
            null
        );

        assertThrows(ReferenceBacktestException.class, () -> service.run(request));
    }

    @Test
    void runAndSaveStoresTaskAndQualityReportCanBeBuilt() {
        InMemoryReferenceBacktestStore store = new InMemoryReferenceBacktestStore();
        ReferenceMvpBacktestService service = new ReferenceMvpBacktestService(new FakeDailyKlineRepository(), store);
        ReferenceQualityService qualityService = new ReferenceQualityService(store);

        ReferenceRunResponse response = service.runAndSave(new ReferenceBacktestRequest(
            "000001",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 7),
            2,
            3,
            null,
            null,
            null
        ));

        assertTrue(store.findById(response.taskId()).isPresent());
        ReferenceQualityReport qualityReport = qualityService.check(response.taskId());
        assertEquals(response.taskId(), qualityReport.taskId());
        assertEquals(3, qualityReport.checks().size());
        assertFalse(qualityReport.suggestions().isEmpty());
    }

    private static final class FakeDailyKlineRepository implements DailyKlineRecordRepository {

        private final List<DailyKlineRecord> bars = List.of(
            bar("2024-01-01", "10.00"),
            bar("2024-01-02", "9.00"),
            bar("2024-01-03", "8.00"),
            bar("2024-01-04", "11.00"),
            bar("2024-01-05", "12.00"),
            bar("2024-01-06", "10.00"),
            bar("2024-01-07", "9.00")
        );

        @Override
        public List<DailyKlineRecord> findByStockCodeAndTradeDateBetween(
            String stockCode,
            LocalDate startDate,
            LocalDate endDate
        ) {
            return bars.stream()
                .filter(bar -> bar.stockCode().equals(stockCode))
                .filter(bar -> !bar.tradeDate().isBefore(startDate) && !bar.tradeDate().isAfter(endDate))
                .toList();
        }

        @Override
        public Optional<DailyKlineRecord> findByTradeDateAndStockCode(LocalDate tradeDate, String stockCode) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public List<DailyKlineRecord> findByStockCode(String stockCode) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public List<DailyKlineRecord> findByTradeDateBetween(LocalDate startDate, LocalDate endDate) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public Optional<DailyKlineRecord> findLatestByStockCode(String stockCode) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public boolean existsByTradeDateAndStockCode(LocalDate tradeDate, String stockCode) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public long countByStockCode(String stockCode) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public List<DailyKlineRecord> findUpDaysByStockCode(String stockCode) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public List<DailyKlineRecord> findDownDaysByStockCode(String stockCode) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public Optional<DailyKlineRecord> findHighestPriceByStockCode(String stockCode) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public Optional<DailyKlineRecord> findLowestPriceByStockCode(String stockCode) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public List<DailyKlineRecord> findLatestByExchange(String exchange) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public List<DailyKlineRecord> findByTradeDate(LocalDate tradeDate) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        @Override
        public List<DailyKlineRecord> findByAmountGreaterThan(BigDecimal minAmount) {
            throw new UnsupportedOperationException("not needed by this unit test");
        }

        private static DailyKlineRecord bar(String date, String close) {
            BigDecimal price = new BigDecimal(close);
            return new DailyKlineRecord(
                LocalDate.parse(date),
                "000001",
                price,
                price,
                price,
                price,
                price,
                1000L,
                price.multiply(new BigDecimal("1000")),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDateTime.parse(date + "T15:00:00")
            );
        }
    }
}
