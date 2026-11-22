package com.chinazhouwy.feishubot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chinazhouwy.miniharness.model.ModelConfig;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * DeepSeek 联网工具：web_search 调 Search API，web_fetch 调 Fetch API。
 *
 * <p>鉴权复用 miniharness-core 的 ModelConfig.apiKey，凭据不写进代码、配置示例或日志。
 */
@Component
public class DeepSeekWebTools {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekWebTools.class);
    private static final String SEARCH_URL = "https://api.deepseek.com/v1/tools/search";
    private static final String FETCH_URL = "https://api.deepseek.com/v1/tools/fetch";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String apiKey;

    public DeepSeekWebTools() throws IOException {
        this(ModelConfig.loadProperties().apiKey());
    }

    /** 便于离线测试注入假 key，不读取真实配置。 */
    public DeepSeekWebTools(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(name = "web_search", readOnly = true,
            description = "用 DeepSeek 搜索实时网页，返回标题、链接和摘要；适合查最新资料。")
    public ToolResultBlock webSearch(
            @ToolParam(name = "query", description = "搜索词，最多 128 字符") String query,
            @ToolParam(name = "count", description = "结果条数，1 到 50，默认 5", required = false) Integer count) {
        if (query == null || query.isBlank()) return ToolResultBlock.error("query 不能为空");
        if (apiKey == null || apiKey.isBlank()) return ToolResultBlock.error("未配置 DeepSeek API Key");
        int n = count != null && count > 0 ? Math.min(count, 50) : 5;
        String body = "{\"query\":" + quote(query) + ",\"count\":" + n + ",\"need_content\":false}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(SEARCH_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return apiError("web_search", response);

            JsonNode results = mapper.readTree(response.body()).path("results");
            if (!results.isArray() || results.isEmpty()) return ToolResultBlock.success("没有搜到结果。");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                JsonNode item = results.get(i);
                out.append(i + 1).append(". ")
                        .append(item.path("title").asText("")).append('\n')
                        .append("   链接: ").append(item.path("url").asText("")).append('\n')
                        .append("   摘要: ").append(item.path("summary").asText("")).append('\n');
            }
            return ToolResultBlock.success(out.toString().strip());
        } catch (Exception e) {
            log.warn("web_search 失败: {}", e.getMessage());
            return ToolResultBlock.error("web_search 失败: " + e.getMessage());
        }
    }

    @Tool(name = "web_fetch", readOnly = true,
            description = "抓取一个 http/https 链接并转成 Markdown 正文，用于阅读网页。")
    public ToolResultBlock webFetch(
            @ToolParam(name = "url", description = "要抓取的 HTTP/HTTPS 链接") String url,
            @ToolParam(name = "max_chars", description = "最多返回多少字符，默认 20000", required = false) Integer maxChars) {
        if (url == null || url.isBlank()) return ToolResultBlock.error("url 不能为空");
        String trimmed = url.strip();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return ToolResultBlock.error("只支持 http/https 链接");
        }
        if (apiKey == null || apiKey.isBlank()) return ToolResultBlock.error("未配置 DeepSeek API Key");
        int limit = maxChars != null && maxChars > 0 ? Math.min(maxChars, 100000) : 20000;
        String body = "{\"url\":" + quote(trimmed) + "}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(FETCH_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return apiError("web_fetch", response);

            JsonNode root = mapper.readTree(response.body());
            String title = root.path("title").asText("");
            String content = root.path("content").asText("");
            if (content.length() > limit) content = content.substring(0, limit) + "\n...[truncated]";
            return ToolResultBlock.success("标题: " + title + "\n\n" + content);
        } catch (Exception e) {
            log.warn("web_fetch 失败: {}", e.getMessage());
            return ToolResultBlock.error("web_fetch 失败: " + e.getMessage());
        }
    }

    private ToolResultBlock apiError(String tool, HttpResponse<String> response) {
        log.warn("{} 返回 HTTP {}", tool, response.statusCode());
        String message = response.body() == null ? response.body() : response.body();
        if (message != null && message.length() > 300) message = message.substring(0, 300);
        return ToolResultBlock.error(tool + " 返回 HTTP " + response.statusCode() + ": " + message)
                .withState(ToolResultState.ERROR);
    }

    private String quote(String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 编码失败", e);
        }
    }
}
