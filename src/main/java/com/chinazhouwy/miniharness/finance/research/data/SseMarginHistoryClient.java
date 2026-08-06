package com.chinazhouwy.miniharness.finance.research.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 上交所融资融券汇总的只读适配器，作为 A 股杠杆历史的权威输入。 */
public final class SseMarginHistoryClient {

    private static final URI ENDPOINT = URI.create("https://query.sse.com.cn/commonSoaQuery.do");
    private static final String REFERER = "https://www.sse.com.cn/market/othersdata/margin/sum/";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SseMarginHistoryClient(ObjectMapper objectMapper) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                objectMapper
        );
    }

    public SseMarginHistoryClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<MarginRow> fetch(LocalDate start, LocalDate end) {
        String form = List.of(
                pair("isPagination", "true"),
                pair("pageHelp.pageSize", "1000"),
                pair("pageHelp.pageNo", "1"),
                pair("pageHelp.beginPage", "1"),
                pair("pageHelp.cacheSize", "1"),
                pair("pageHelp.endPage", "1"),
                pair("stockCode", ""),
                pair("beginDate", compact(start)),
                pair("endDate", compact(end)),
                pair("sqlId", "RZRQ_HZ_INFO")
        ).stream().collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(30))
                .header("Referer", REFERER)
                .header("User-Agent", "MiniHarness/0.3 read-only research")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("SSE margin endpoint returned HTTP " + response.statusCode());
            }
            JsonNode rows = objectMapper.readTree(response.body()).path("pageHelp").path("data");
            if (!rows.isArray()) {
                throw new IllegalStateException("SSE margin endpoint returned no pageHelp.data");
            }
            List<MarginRow> result = new ArrayList<>();
            for (JsonNode row : rows) {
                String dateText = row.path("opDate").asText("");
                if (dateText.length() != 8) continue;
                result.add(new MarginRow(
                        LocalDate.parse(dateText, java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                        number(row, "rzye"),
                        number(row, "rzmre"),
                        number(row, "rzche")
                ));
            }
            return result.stream().sorted(java.util.Comparator.comparing(MarginRow::tradeDate)).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot parse SSE margin response", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading SSE margin history", exception);
        }
    }

    private static long number(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isNumber()) return value.longValue();
        return Long.parseLong(value.asText("0"));
    }

    private static String pair(String name, String value) {
        return java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "="
                + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String compact(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    public record MarginRow(
            LocalDate tradeDate,
            long financingBalanceYuan,
            long financingBuyYuan,
            long financingRepaymentYuan
    ) {
    }
}
