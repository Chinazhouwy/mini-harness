package com.chinazhouwy.miniharness.agentscope.study;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Scanner;

/**
 * registerSchema 那类「框架不执行」的工具，结果从哪来都行。
 *
 * <p>下面四种写法全都是普通 Java 代码，框架一个都不提供。框架只做两件事：
 * 挂起并把 ToolUseBlock 交回来、接收一个「字符串 + 同一个 callId」的回填。
 * 所以 RegisterSchemaTest 里那句
 * <pre>{@code
 * String externalResult = readExternalResult();
 * }</pre>
 * 换成下面任意一个方法即可，后面的回填代码一行都不用改。
 */
public final class ExternalResultSources {

    private ExternalResultSources() {
    }

    /** 场景一：人坐在终端前敲一行（RegisterSchemaTest 现在用的就是这种） */
    public static String fromConsole(String prompt) {
        System.out.print("  > " + prompt + "：");
        System.out.flush();
        Scanner in = new Scanner(System.in);
        String line = in.hasNextLine() ? in.nextLine() : null;
        return line == null || line.isBlank() ? "（没读到输入）" : line.trim();
    }

    /** 场景二：查库。这里用一张本地 Map 代替你的 DAO / JdbcTemplate / MyBatis */
    private static final Map<String, Integer> STOCK = Map.of(
            "SKU-9527", 42,
            "SKU-1001", 0,
            "SKU-8888", 7);

    public static String fromDatabase(String sku) {
        Integer count = STOCK.get(sku);
        return count == null
                ? "查无此商品：" + sku
                : sku + " 当前库存 " + count + " 件（库存表返回）";
    }

    /** 场景三：调别人的 HTTP 接口。url 换成你自己的服务地址 */
    public static String fromHttp(String url, String sku) {
        try {
            String query = URLEncoder.encode(sku, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url + "?sku=" + query))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200
                    ? resp.body()
                    : "接口返回 " + resp.statusCode() + "：" + resp.body();
        } catch (Exception e) {
            // 查失败也回填一句，让模型知道没查到，而不是干等
            return "接口调用失败：" + e.getMessage();
        }
    }

    /** 场景四：审批流。这里用「等一个人敲 y/n」代表审批系统给的结论 */
    public static String fromApproval(String sku) {
        String decision = fromConsole("批准 " + sku + " 出库吗？(y/n)");
        return "y".equalsIgnoreCase(decision)
                ? sku + " 出库已批准，可以执行"
                : sku + " 出库被拒绝，不要执行";
    }

    /** 不调模型、不发网络请求，只把「取结果」这一步跑给你看 */
    public static void main(String[] args) {
        System.out.println("【查库·有】    " + fromDatabase("SKU-9527"));
        System.out.println("【查库·没有】  " + fromDatabase("SKU-9999"));
        System.out.println("【查库·零库存】" + fromDatabase("SKU-1001"));
        System.out.println();
        System.out.println("其余三种都是同一行的替换，不在这里执行：");
        System.out.println("  fromConsole(\"输入库存结果\")                // 等人敲");
        System.out.println("  fromHttp(\"http://your-service/stock\", sku) // 调接口");
        System.out.println("  fromApproval(sku)                          // 等审批结论");
    }
}
