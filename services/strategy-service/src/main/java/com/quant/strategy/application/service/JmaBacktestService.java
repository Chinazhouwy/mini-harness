package com.quant.strategy.application.service;

import com.quant.strategy.application.dto.BacktestRequestDto;
import com.quant.strategy.application.dto.BacktestResultDto;
import com.quant.strategy.application.dto.SignalDto;
import com.quant.strategy.domain.record.DailyKlineRecord;
import com.quant.strategy.domain.repository.DailyKlineRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.averages.JMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.time.Duration;
import java.time.Instant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JMA回测服务（简化版本）
 * 使用Ta4j的JMA（Jurik Moving Average）指标进行策略回测
 */
@Service
public class JmaBacktestService {
    
    private static final Logger log = LoggerFactory.getLogger(JmaBacktestService.class);
    
    private final DailyKlineRecordRepository klineRepository;
    
    public JmaBacktestService(DailyKlineRecordRepository klineRepository) {
        this.klineRepository = klineRepository;
    }
    
    /**
     * 执行JMA回测（简化版本，先让服务跑起来）
     */
    public BacktestResultDto runBacktest(BacktestRequestDto request) {
        try {
            log.info("开始JMA回测: 股票={}, 时间范围={}-{}", 
                    request.stockCode(), request.startDate(), request.endDate());
            
            // 1. 从数据库获取K线数据
            List<DailyKlineRecord> klines = klineRepository
                .findByStockCodeAndTradeDateBetween(
                    request.stockCode(),
                    request.startDate(),
                    request.endDate()
                );
            
            if (klines.isEmpty()) {
                log.warn("未找到K线数据: {}", request.stockCode());
                return BacktestResultDto.failure(request.stockCode(), "未找到历史数据");
            }
            
            log.info("获取到{}条K线数据", klines.size());
            
            // 2. 构建Ta4j BarSeries（反转列表，数据库返回DESC顺序）
            List<DailyKlineRecord> reversedKlines = new ArrayList<>(klines);
            Collections.reverse(reversedKlines); // 改为ASC顺序
            
            BarSeries series = buildBarSeries(reversedKlines);
            
            // 3. 创建JMA指标
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            
            // JMA参数：period, phase, power
            int period = request.period();
            int phase = request.phase().intValue();  // Double转int
            int power = request.power().intValue();  // Double转int
            
            JMAIndicator jmaIndicator = new JMAIndicator(closePrice, period, phase, power);
            
            log.info("JMA指标创建完成: period={}, phase={}, power={}", period, phase, power);
            
            // 4. 简化回测逻辑：直接遍历BarSeries查找交叉点
            List<SignalDto> signals = new ArrayList<>();
            int tradeCount = 0;
            BigDecimal totalProfit = BigDecimal.ZERO;
            BigDecimal maxDrawdown = BigDecimal.ZERO;
            int winningTrades = 0;
            
            BigDecimal buyPrice = BigDecimal.ZERO;
            BigDecimal peakValue = BigDecimal.ONE; // 初始资金1.0
            BigDecimal currentValue = BigDecimal.ONE;
            
            // 遍历查找JMA交叉信号
            for (int i = 1; i < series.getBarCount(); i++) {
                org.ta4j.core.num.Num prevClose = closePrice.getValue(i-1);
                org.ta4j.core.num.Num currClose = closePrice.getValue(i);
                org.ta4j.core.num.Num prevJma = jmaIndicator.getValue(i-1);
                org.ta4j.core.num.Num currJma = jmaIndicator.getValue(i);
                
                // 上穿JMA（买入信号）
                if (prevClose.isLessThan(prevJma) && currClose.isGreaterThanOrEqual(currJma)) {
                    org.ta4j.core.Bar bar = series.getBar(i);
                    LocalDate signalDate = bar.getEndTime().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                    BigDecimal signalPrice = BigDecimal.valueOf(bar.getClosePrice().doubleValue());
                    
                    signals.add(SignalDto.buy(signalDate, signalPrice, 
                        BigDecimal.valueOf(currJma.doubleValue()), "收盘价上穿JMA"));
                    
                    if (buyPrice.compareTo(BigDecimal.ZERO) == 0) {
                        buyPrice = signalPrice; // 开仓
                    }
                }
                
                // 下穿JMA（卖出信号）
                if (prevClose.isGreaterThan(prevJma) && currClose.isLessThanOrEqual(currJma)) {
                    org.ta4j.core.Bar bar = series.getBar(i);
                    LocalDate signalDate = bar.getEndTime().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                    BigDecimal signalPrice = BigDecimal.valueOf(bar.getClosePrice().doubleValue());
                    
                    signals.add(SignalDto.sell(signalDate, signalPrice,
                        BigDecimal.valueOf(currJma.doubleValue()), "收盘价下穿JMA"));
                    
                    if (buyPrice.compareTo(BigDecimal.ZERO) > 0) {
                        // 平仓，计算收益
                        BigDecimal profit = signalPrice.subtract(buyPrice)
                            .divide(buyPrice, 4, RoundingMode.HALF_UP);
                        totalProfit = totalProfit.add(profit);
                        currentValue = currentValue.multiply(BigDecimal.ONE.add(profit));
                        
                        // 更新最大回撤
                        if (currentValue.compareTo(peakValue) > 0) {
                            peakValue = currentValue;
                        }
                        BigDecimal drawdown = peakValue.subtract(currentValue)
                            .divide(peakValue, 4, RoundingMode.HALF_UP);
                        if (drawdown.compareTo(maxDrawdown) > 0) {
                            maxDrawdown = drawdown;
                        }
                        
                        if (profit.compareTo(BigDecimal.ZERO) > 0) {
                            winningTrades++;
                        }
                        
                        tradeCount++;
                        buyPrice = BigDecimal.ZERO; // 重置
                    }
                }
            }
            
            // 5. 计算简化指标
            BigDecimal totalReturn = totalProfit;
            
            // 计算年化收益和夏普比率
            long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(
                request.startDate(), request.endDate()
            );
            double years = daysBetween / 365.25;
            BigDecimal annualReturn = years > 0 
                ? totalReturn.divide(BigDecimal.valueOf(years), 4, RoundingMode.HALF_UP)
                : totalReturn;
            
            BigDecimal sharpeRatio = maxDrawdown.compareTo(BigDecimal.ZERO) > 0
                ? annualReturn.divide(maxDrawdown, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            
            BigDecimal winRate = tradeCount > 0
                ? BigDecimal.valueOf(winningTrades).divide(BigDecimal.valueOf(tradeCount), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            
            return BacktestResultDto.success(
                request.stockCode(),
                "JMA Strategy (period=" + period + ", phase=" + phase + ", power=" + power + ")",
                request.startDate(),
                request.endDate(),
                totalReturn.setScale(4, RoundingMode.HALF_UP),
                annualReturn.setScale(4, RoundingMode.HALF_UP),
                maxDrawdown.setScale(4, RoundingMode.HALF_UP),
                sharpeRatio.setScale(4, RoundingMode.HALF_UP),
                tradeCount,
                winRate.setScale(4, RoundingMode.HALF_UP),
                BigDecimal.ZERO, // profitFactor简化为0
                signals
            );
            
        } catch (Exception e) {
            log.error("回测执行失败", e);
            return BacktestResultDto.failure(request.stockCode(), "回测失败: " + e.getMessage());
        }
    }
    
    /**
     * 从K线记录构建Ta4j BarSeries（使用正确的API）
     */
    private BarSeries buildBarSeries(List<DailyKlineRecord> klines) {
        BarSeries series = new BaseBarSeriesBuilder().build();
        Duration barDuration = Duration.ofDays(1); // 日K线
        
        for (DailyKlineRecord kline : klines) {
            // 时间转换：LocalDate -> Instant
            Instant endTime = kline.tradeDate()
                .atTime(15, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
            Instant startTime = endTime.minus(barDuration);
            
            // Num类型转换（使用DecimalNum）
            Num open = DecimalNum.valueOf(kline.open() != null ? kline.open().doubleValue() : 0.0);
            Num high = DecimalNum.valueOf(kline.high() != null ? kline.high().doubleValue() : 0.0);
            Num low = DecimalNum.valueOf(kline.low() != null ? kline.low().doubleValue() : 0.0);
            Num close = DecimalNum.valueOf(kline.close() != null ? kline.close().doubleValue() : 0.0);
            Num volume = DecimalNum.valueOf(kline.volume() != null ? kline.volume().doubleValue() : 0.0);
            Num amount = DecimalNum.valueOf(kline.amount() != null ? kline.amount().doubleValue() : 0.0);
            long trades = 0; // 交易笔数（简化）
            
            // 创建BaseBar并添加（使用正确的构造函数）
            BaseBar bar = new BaseBar(barDuration, startTime, endTime, open, high, low, close, volume, amount, trades);
            series.addBar(bar);
        }
        
        return series;
    }
}