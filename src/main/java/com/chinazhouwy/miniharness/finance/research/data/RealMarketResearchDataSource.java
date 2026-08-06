package com.chinazhouwy.miniharness.finance.research.data;

import com.chinazhouwy.miniharness.finance.market.domain.SourceEvidence;
import com.chinazhouwy.miniharness.finance.research.domain.MarketInstrument;
import com.chinazhouwy.miniharness.finance.research.domain.MarketObservation;
import com.chinazhouwy.miniharness.finance.research.domain.MarketResearchDataset;
import com.chinazhouwy.miniharness.finance.research.domain.MarketSeries;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 真实数据研究源：A 股使用上交所两融历史，港股/美股使用 WeStock 卖空数据，价格使用 WeStock 日 K。
 * 某个市场失败时会跳过该市场并保留其它成功市场；如果全部失败则明确抛错，不降级为 Fixture。
 */
public final class RealMarketResearchDataSource implements MarketResearchDataSource {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final MarketInstrument A_SHARE = new MarketInstrument(
            "a-share", "A股", "sh000001", "上证综指", "CNY",
            "MARGIN_BALANCE", "万亿元",
            "上交所融资余额 / 10^12 元"
    );
    private static final MarketInstrument HONG_KONG = new MarketInstrument(
            "hong-kong", "港股", "hk00700", "腾讯控股", "HKD",
            "SHORT_RATIO", "%",
            "WeStock ShortRatio（卖空比例，供应商字段）"
    );
    private static final MarketInstrument UNITED_STATES = new MarketInstrument(
            "united-states", "美股", "usAAPL", "Apple", "USD",
            "SHORT_RATIO", "%",
            "WeStock ShortRatio（卖空比例，供应商字段）"
    );

    private final WestockCommandClient westock;
    private final SseMarginHistoryClient sseMargin;
    private final Clock clock;
    private final int lookbackDays;

    public RealMarketResearchDataSource(ObjectMapper objectMapper) {
        this(objectMapper, Clock.system(SHANGHAI), 420);
    }

    public RealMarketResearchDataSource(ObjectMapper objectMapper, Clock clock, int lookbackDays) {
        this(new WestockCommandClient(objectMapper), new SseMarginHistoryClient(objectMapper), clock, lookbackDays);
    }

    public RealMarketResearchDataSource(
            WestockCommandClient westock,
            SseMarginHistoryClient sseMargin,
            Clock clock,
            int lookbackDays
    ) {
        this.westock = Objects.requireNonNull(westock, "westock");
        this.sseMargin = Objects.requireNonNull(sseMargin, "sseMargin");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (lookbackDays < 60) throw new IllegalArgumentException("lookbackDays must be at least 60");
        this.lookbackDays = lookbackDays;
    }

    @Override
    public MarketResearchDataset load() {
        LocalDate end = LocalDate.now(clock);
        LocalDate start = end.minusDays(lookbackDays);
        Instant retrievedAt = Instant.now(clock);
        List<MarketSeries> result = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            result.add(loadAshare(start, end, retrievedAt));
        } catch (RuntimeException exception) {
            failures.add("A股: " + message(exception));
        }
        try {
            result.add(loadShortSeries(HONG_KONG, start, end, retrievedAt));
        } catch (RuntimeException exception) {
            failures.add("港股: " + message(exception));
        }
        try {
            result.add(loadShortSeries(UNITED_STATES, start, end, retrievedAt));
        } catch (RuntimeException exception) {
            failures.add("美股: " + message(exception));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("真实杠杆数据全部不可用: " + String.join("; ", failures));
        }
        List<SourceEvidence> sources = result.stream()
                .flatMap(series -> series.sources().stream())
                .distinct()
                .toList();
        return new MarketResearchDataset(retrievedAt, result, sources);
    }

    private MarketSeries loadAshare(LocalDate start, LocalDate end, Instant retrievedAt) {
        List<SseMarginHistoryClient.MarginRow> marginRows = sseMargin.fetch(start, end);
        if (marginRows.size() < 30) {
            throw new IllegalStateException("上交所两融历史只有 " + marginRows.size() + " 条");
        }
        Map<LocalDate, SseMarginHistoryClient.MarginRow> marginByDate = new HashMap<>();
        marginRows.forEach(row -> marginByDate.put(row.tradeDate(), row));
        List<MarketObservation> observations = new ArrayList<>();
        for (JsonNode price : sortedNodes(westock.kline(A_SHARE.symbol(), start, end), "date")) {
            LocalDate date = date(price, "date");
            SseMarginHistoryClient.MarginRow margin = marginByDate.get(date);
            if (margin == null) continue;
            observations.add(new MarketObservation(
                    date,
                    close(price),
                    margin.financingBalanceYuan() / 1_000_000_000_000.0,
                    false
            ));
        }
        if (observations.size() < 30) {
            throw new IllegalStateException("A股价格与两融历史只能对齐 " + observations.size() + " 条");
        }
        observations.sort(Comparator.comparing(MarketObservation::tradeDate));
        SourceEvidence marginSource = new SourceEvidence(
                "sse-margin-" + start + "-" + end,
                "上海证券交易所融资融券汇总",
                "https://www.sse.com.cn/market/othersdata/margin/sum/",
                observations.getLast().tradeDate(),
                retrievedAt,
                "opDate,rzye,rzmre,rzche",
                "yuan",
                "融资余额、融资买入额和融资偿还额；A股杠杆输入只取上交所汇总口径"
        );
        SourceEvidence priceSource = priceEvidence(A_SHARE, observations.getLast().tradeDate(), retrievedAt);
        return new MarketSeries(A_SHARE, observations, List.of(marginSource, priceSource));
    }

    private MarketSeries loadShortSeries(
            MarketInstrument instrument,
            LocalDate start,
            LocalDate end,
            Instant retrievedAt
    ) {
        Map<LocalDate, Double> shortRatio = new HashMap<>();
        for (JsonNode row : asArray(westock.shortSelling(instrument.symbol(), start, end))) {
            JsonNode ratio = row.get("ShortRatio");
            if (ratio == null || ratio.isNull() || ratio.asText().isBlank()) continue;
            shortRatio.put(date(row, "date", "EndDate"), ratio.asDouble());
        }
        if (shortRatio.isEmpty()) {
            throw new IllegalStateException("卖空接口没有返回 ShortRatio");
        }
        List<MarketObservation> observations = new ArrayList<>();
        Double latestRatio = null;
        for (JsonNode price : sortedNodes(westock.kline(instrument.symbol(), start, end), "date")) {
            LocalDate date = date(price, "date");
            if (shortRatio.containsKey(date)) latestRatio = shortRatio.get(date);
            if (latestRatio == null) continue;
            observations.add(new MarketObservation(
                    date,
                    close(price),
                    latestRatio,
                    !shortRatio.containsKey(date)
            ));
        }
        observations.sort(Comparator.comparing(MarketObservation::tradeDate));
        if (observations.size() < 30) {
            throw new IllegalStateException(instrument.market() + "价格与卖空数据只能对齐 " + observations.size() + " 条");
        }
        SourceEvidence leverageSource = new SourceEvidence(
                "westock-short-" + instrument.symbol() + "-" + start + "-" + end,
                "WeStock 腾讯自选股卖空数据",
                "skill://westock-data/fund-short/" + instrument.symbol(),
                observations.getLast().tradeDate(),
                retrievedAt,
                "ShortRatio,ShortShares,ShortRecoverDays",
                "%",
                "ShortRatio 是供应商字段；缺少报告日时以前值向前填充，并在图表中标记"
        );
        SourceEvidence priceSource = priceEvidence(instrument, observations.getLast().tradeDate(), retrievedAt);
        return new MarketSeries(instrument, observations, List.of(leverageSource, priceSource));
    }

    private SourceEvidence priceEvidence(MarketInstrument instrument, LocalDate date, Instant retrievedAt) {
        return new SourceEvidence(
                "westock-kline-" + instrument.symbol(),
                "WeStock 腾讯自选股日 K 线",
                "skill://westock-data/kline/" + instrument.symbol(),
                date,
                retrievedAt,
                "date,last,open,high,low,volume,amount",
                instrument.currency(),
                "K线可能延迟；页面展示交易日期，不宣称盘中实时"
        );
    }

    private static JsonNode asArray(JsonNode root) {
        if (root.isArray()) return root;
        JsonNode data = root.path("data");
        if (data.isArray()) return data;
        throw new IllegalStateException("WeStock 返回不是数组: " + root);
    }

    private static List<JsonNode> sortedNodes(JsonNode root, String... dateFields) {
        List<JsonNode> nodes = new ArrayList<>();
        asArray(root).forEach(nodes::add);
        nodes.sort(Comparator.comparing(node -> date(node, dateFields)));
        return nodes;
    }

    private static LocalDate date(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) return LocalDate.parse(value.substring(0, 10));
        }
        throw new IllegalStateException("数据缺少日期字段: " + node);
    }

    private static double close(JsonNode node) {
        JsonNode value = node.get("last");
        if (value == null) value = node.get("ClosePrice");
        if (value == null || !value.isNumber() && value.asText().isBlank()) {
            throw new IllegalStateException("数据缺少收盘价: " + node);
        }
        return value.asDouble();
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
