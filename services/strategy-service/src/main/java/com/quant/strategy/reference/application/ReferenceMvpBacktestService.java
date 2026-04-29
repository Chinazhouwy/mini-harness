package com.quant.strategy.reference.application;

import com.quant.strategy.domain.record.DailyKlineRecord;
import com.quant.strategy.domain.repository.DailyKlineRecordRepository;
import com.quant.strategy.reference.domain.BacktestMetrics;
import com.quant.strategy.reference.domain.BacktestTaskStatus;
import com.quant.strategy.reference.domain.EquityPoint;
import com.quant.strategy.reference.domain.MarketBar;
import com.quant.strategy.reference.domain.OrderSide;
import com.quant.strategy.reference.domain.ReferenceBacktestReport;
import com.quant.strategy.reference.domain.ReferenceBacktestTask;
import com.quant.strategy.reference.domain.ReferenceRunResponse;
import com.quant.strategy.reference.domain.RiskDecision;
import com.quant.strategy.reference.domain.SignalType;
import com.quant.strategy.reference.domain.SimulatedOrder;
import com.quant.strategy.reference.domain.TradingSignal;
import com.quant.strategy.reference.infrastructure.InMemoryReferenceBacktestStore;
import com.quant.strategy.reference.infrastructure.ReferenceBacktestStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 单人 MVP 参考实现：行情 -> 策略 -> 风控 -> 模拟订单 -> 绩效指标。
 *
 * <p>这不是追求最优收益的策略，而是一条工程学习链路。你后面可以逐段替换：
 * 把双均线替换成 JMA，把内存模拟订单替换成 PostgreSQL，把同步调用替换成消息。</p>
 */
@Service
public class ReferenceMvpBacktestService {

    private static final int MONEY_SCALE = 4;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    private final DailyKlineRecordRepository klineRepository;
    private final ReferenceBacktestStore store;

    public ReferenceMvpBacktestService(DailyKlineRecordRepository klineRepository, ReferenceBacktestStore store) {
        this.klineRepository = klineRepository;
        this.store = store;
    }

    /**
     * 测试专用构造器，避免单元测试必须启动 Spring 容器。
     */
    ReferenceMvpBacktestService(DailyKlineRecordRepository klineRepository) {
        this(klineRepository, new InMemoryReferenceBacktestStore());
    }

    /**
     * 执行回测并保存任务记录。
     */
    public ReferenceRunResponse runAndSave(ReferenceBacktestRequest request) {
        String taskId = UUID.randomUUID().toString();
        LocalDateTime createdAt = LocalDateTime.now();

        try {
            ReferenceBacktestReport report = run(request);
            store.save(ReferenceBacktestTask.success(taskId, request, report, createdAt));
            return new ReferenceRunResponse(taskId, BacktestTaskStatus.SUCCESS, report, null);
        } catch (RuntimeException exception) {
            store.save(ReferenceBacktestTask.failed(taskId, request, exception.getMessage(), createdAt));
            return new ReferenceRunResponse(taskId, BacktestTaskStatus.FAILED, null, exception.getMessage());
        }
    }

    /**
     * 执行完整参考回测。
     */
    public ReferenceBacktestReport run(ReferenceBacktestRequest request) {
        validateRequest(request);

        List<MarketBar> bars = loadBars(request);
        if (bars.size() < request.slowWindow() + 1) {
            throw new ReferenceBacktestException(
                "Not enough bars. required at least " + (request.slowWindow() + 1) + ", actual " + bars.size()
            );
        }

        AccountState account = new AccountState(request.initialCash());
        List<TradingSignal> signals = new ArrayList<>();
        List<SimulatedOrder> orders = new ArrayList<>();
        List<EquityPoint> equityCurve = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (int index = 1; index < bars.size(); index++) {
            MarketBar today = bars.get(index);

            BigDecimal todayFast = movingAverage(bars, index, request.fastWindow());
            BigDecimal todaySlow = movingAverage(bars, index, request.slowWindow());
            BigDecimal yesterdayFast = movingAverage(bars, index - 1, request.fastWindow());
            BigDecimal yesterdaySlow = movingAverage(bars, index - 1, request.slowWindow());

            if (todayFast == null || todaySlow == null || yesterdayFast == null || yesterdaySlow == null) {
                equityCurve.add(account.snapshot(today));
                continue;
            }

            TradingSignal signal = decideSignal(today, yesterdayFast, yesterdaySlow, todayFast, todaySlow, account, request);
            if (signal.type() != SignalType.HOLD) {
                signals.add(signal);
                RiskDecision riskDecision = checkRisk(signal, account, request);

                if (riskDecision.allowed()) {
                    SimulatedOrder order = execute(signal, account, request, riskDecision.reason());
                    if (order != null) {
                        orders.add(order);
                    }
                } else {
                    warnings.add(signal.tradeDate() + " " + signal.type() + " rejected: " + riskDecision.reason());
                }
            }

            equityCurve.add(account.snapshot(today));
        }

        BacktestMetrics metrics = calculateMetrics(request.initialCash(), orders, equityCurve);
        return new ReferenceBacktestReport(
            request.stockCode(),
            request.startDate(),
            request.endDate(),
            request.fastWindow(),
            request.slowWindow(),
            metrics,
            List.copyOf(signals),
            List.copyOf(orders),
            List.copyOf(equityCurve),
            List.copyOf(warnings)
        );
    }

    private void validateRequest(ReferenceBacktestRequest request) {
        if (request.startDate().isAfter(request.endDate())) {
            throw new ReferenceBacktestException("startDate must be before or equal to endDate");
        }
        if (request.fastWindow() < 2) {
            throw new ReferenceBacktestException("fastWindow must be >= 2");
        }
        if (request.slowWindow() <= request.fastWindow()) {
            throw new ReferenceBacktestException("slowWindow must be greater than fastWindow");
        }
        if (request.initialCash().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ReferenceBacktestException("initialCash must be positive");
        }
        if (request.maxPositionRatio().compareTo(BigDecimal.ZERO) <= 0
            || request.maxPositionRatio().compareTo(BigDecimal.ONE) > 0) {
            throw new ReferenceBacktestException("maxPositionRatio must be in (0, 1]");
        }
        if (request.stopLossRatio().compareTo(BigDecimal.ZERO) < 0) {
            throw new ReferenceBacktestException("stopLossRatio must be >= 0");
        }
    }

    private List<MarketBar> loadBars(ReferenceBacktestRequest request) {
        List<DailyKlineRecord> records = klineRepository.findByStockCodeAndTradeDateBetween(
            request.stockCode(),
            request.startDate(),
            request.endDate()
        );

        return records.stream()
            .map(MarketBar::from)
            .sorted(Comparator.comparing(MarketBar::tradeDate))
            .toList();
    }

    /**
     * 计算简单移动平均线。
     *
     * <p>index 是当前 K 线下标，窗口包含当前 K 线。例如 index=10/window=5，
     * 会计算 [6, 10] 这五根 K 线的收盘价均值。</p>
     */
    private BigDecimal movingAverage(List<MarketBar> bars, int index, int window) {
        int from = index - window + 1;
        if (from < 0) {
            return null;
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (int cursor = from; cursor <= index; cursor++) {
            sum = sum.add(bars.get(cursor).close());
        }
        return sum.divide(BigDecimal.valueOf(window), MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private TradingSignal decideSignal(
        MarketBar bar,
        BigDecimal yesterdayFast,
        BigDecimal yesterdaySlow,
        BigDecimal todayFast,
        BigDecimal todaySlow,
        AccountState account,
        ReferenceBacktestRequest request
    ) {
        boolean goldenCross = yesterdayFast.compareTo(yesterdaySlow) <= 0 && todayFast.compareTo(todaySlow) > 0;
        boolean deadCross = yesterdayFast.compareTo(yesterdaySlow) >= 0 && todayFast.compareTo(todaySlow) < 0;

        if (goldenCross && !account.hasPosition()) {
            return new TradingSignal(
                bar.tradeDate(),
                bar.stockCode(),
                SignalType.BUY,
                bar.close(),
                todayFast,
                todaySlow,
                "fast moving average crosses above slow moving average"
            );
        }

        if (account.hasPosition()) {
            BigDecimal lossRatio = account.unrealizedReturn(bar.close()).negate();
            if (deadCross) {
                return sellSignal(bar, todayFast, todaySlow, "fast moving average crosses below slow moving average");
            }
            if (lossRatio.compareTo(request.stopLossRatio()) >= 0) {
                return sellSignal(bar, todayFast, todaySlow, "stop loss threshold reached");
            }
        }

        return new TradingSignal(
            bar.tradeDate(),
            bar.stockCode(),
            SignalType.HOLD,
            bar.close(),
            todayFast,
            todaySlow,
            "no actionable crossover"
        );
    }

    private TradingSignal sellSignal(MarketBar bar, BigDecimal fastAverage, BigDecimal slowAverage, String reason) {
        return new TradingSignal(
            bar.tradeDate(),
            bar.stockCode(),
            SignalType.SELL,
            bar.close(),
            fastAverage,
            slowAverage,
            reason
        );
    }

    private RiskDecision checkRisk(TradingSignal signal, AccountState account, ReferenceBacktestRequest request) {
        if (signal.type() == SignalType.BUY) {
            BigDecimal targetAmount = account.totalEquity(signal.price()).multiply(request.maxPositionRatio());
            if (targetAmount.compareTo(signal.price()) < 0) {
                return RiskDecision.reject("cash is too small to buy one unit");
            }
            return RiskDecision.allow("position ratio within limit: " + request.maxPositionRatio());
        }

        if (signal.type() == SignalType.SELL && account.hasPosition()) {
            BigDecimal currentReturn = account.unrealizedReturn(signal.price());
            if (currentReturn.negate().compareTo(request.stopLossRatio()) >= 0) {
                return RiskDecision.allow("stop loss triggered: " + currentReturn);
            }
            return RiskDecision.allow("sell signal accepted");
        }

        return RiskDecision.reject("no position to sell");
    }

    private SimulatedOrder execute(
        TradingSignal signal,
        AccountState account,
        ReferenceBacktestRequest request,
        String riskReason
    ) {
        if (signal.type() == SignalType.BUY) {
            BigDecimal investAmount = account.totalEquity(signal.price()).multiply(request.maxPositionRatio());
            BigDecimal availableAmount = investAmount.min(account.cash);
            BigDecimal quantity = availableAmount.divide(signal.price(), MONEY_SCALE, RoundingMode.DOWN);
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }

            BigDecimal amount = quantity.multiply(signal.price()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            account.buy(quantity, amount, signal.price());
            return newOrder(signal, OrderSide.BUY, quantity, amount, riskReason);
        }

        BigDecimal quantity = account.positionQuantity;
        BigDecimal amount = quantity.multiply(signal.price()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        account.sell(amount, signal.price());
        return newOrder(signal, OrderSide.SELL, quantity, amount, riskReason);
    }

    private SimulatedOrder newOrder(
        TradingSignal signal,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal amount,
        String riskReason
    ) {
        String orderId = signal.stockCode() + "-" + signal.tradeDate() + "-" + side;
        return new SimulatedOrder(
            orderId,
            signal.tradeDate(),
            signal.stockCode(),
            side,
            signal.price(),
            quantity,
            amount,
            riskReason
        );
    }

    private BacktestMetrics calculateMetrics(
        BigDecimal initialCash,
        List<SimulatedOrder> orders,
        List<EquityPoint> equityCurve
    ) {
        BigDecimal finalEquity = equityCurve.isEmpty()
            ? initialCash
            : equityCurve.get(equityCurve.size() - 1).totalEquity();
        BigDecimal totalReturn = finalEquity.subtract(initialCash)
            .divide(initialCash, MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal maxDrawdown = equityCurve.stream()
            .map(EquityPoint::drawdown)
            .max(BigDecimal::compareTo)
            .orElse(ZERO);

        int tradeCount = (int) orders.stream().filter(order -> order.side() == OrderSide.SELL).count();
        int winningTrades = countWinningTrades(orders);
        BigDecimal winRate = tradeCount == 0
            ? ZERO
            : BigDecimal.valueOf(winningTrades).divide(BigDecimal.valueOf(tradeCount), MONEY_SCALE, RoundingMode.HALF_UP);

        return new BacktestMetrics(
            initialCash.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            finalEquity.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            totalReturn,
            maxDrawdown,
            tradeCount,
            winningTrades,
            winRate
        );
    }

    private int countWinningTrades(List<SimulatedOrder> orders) {
        int winningTrades = 0;
        SimulatedOrder lastBuy = null;

        for (SimulatedOrder order : orders) {
            if (order.side() == OrderSide.BUY) {
                lastBuy = order;
            } else if (lastBuy != null && order.price().compareTo(lastBuy.price()) > 0) {
                winningTrades++;
                lastBuy = null;
            }
        }

        return winningTrades;
    }

    /**
     * 回测过程里的临时账户状态。
     *
     * <p>它是 service 的私有实现细节，不暴露到 Controller。这样外部 API 只看到
     * 请求和报告，内部怎么记账可以继续演进。</p>
     */
    private static final class AccountState {
        private BigDecimal cash;
        private BigDecimal positionQuantity = BigDecimal.ZERO;
        private BigDecimal averageCost = BigDecimal.ZERO;
        private BigDecimal peakEquity;

        private AccountState(BigDecimal initialCash) {
            this.cash = initialCash.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            this.peakEquity = this.cash;
        }

        private boolean hasPosition() {
            return positionQuantity.compareTo(BigDecimal.ZERO) > 0;
        }

        private BigDecimal totalEquity(BigDecimal closePrice) {
            BigDecimal marketValue = positionQuantity.multiply(closePrice);
            return cash.add(marketValue).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private BigDecimal unrealizedReturn(BigDecimal closePrice) {
            if (!hasPosition() || averageCost.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            return closePrice.subtract(averageCost).divide(averageCost, MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private void buy(BigDecimal quantity, BigDecimal amount, BigDecimal price) {
            cash = cash.subtract(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            positionQuantity = positionQuantity.add(quantity).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            averageCost = price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private void sell(BigDecimal amount, BigDecimal price) {
            cash = cash.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            positionQuantity = BigDecimal.ZERO;
            averageCost = price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private EquityPoint snapshot(MarketBar bar) {
            BigDecimal equity = totalEquity(bar.close());
            if (equity.compareTo(peakEquity) > 0) {
                peakEquity = equity;
            }

            BigDecimal drawdown = peakEquity.compareTo(BigDecimal.ZERO) == 0
                ? ZERO
                : peakEquity.subtract(equity).divide(peakEquity, MONEY_SCALE, RoundingMode.HALF_UP);

            return new EquityPoint(
                bar.tradeDate(),
                cash,
                positionQuantity,
                bar.close(),
                equity,
                drawdown
            );
        }
    }
}
