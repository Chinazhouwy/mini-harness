package com.chinazhouwy.miniharness.feishu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 飞书应用凭证。优先读环境变量，其次读 -Dfeishu.config / FEISHU_CONFIG 指定的本地文件。
 * 配置文件只保存在本机且已加入 .gitignore；日志和异常只打印配置路径，不打印密钥。
 */
public record AppCredentials(String appId, String appSecret) {
    private static final String APP_ID_ENV = "FEISHU_APP_ID";
    private static final String APP_SECRET_ENV = "FEISHU_APP_SECRET";
    private static final String CONFIG_PROPERTY = "feishu.config";
    private static final String CONFIG_ENV = "FEISHU_CONFIG";

    public AppCredentials {
        require(appId, "FEISHU_APP_ID / feishu.app.id");
        require(appSecret, "FEISHU_APP_SECRET / feishu.app.secret");
    }

    public static AppCredentials load() throws IOException {
        String envId = trimToNull(System.getenv(APP_ID_ENV));
        String envSecret = trimToNull(System.getenv(APP_SECRET_ENV));
        if (envId != null || envSecret != null) {
            if (envId == null || envSecret == null) {
                throw new IOException("飞书环境变量必须同时设置 " + APP_ID_ENV + " 和 " + APP_SECRET_ENV);
            }
            return new AppCredentials(envId, envSecret);
        }

        String configuredPath = trimToNull(System.getProperty(CONFIG_PROPERTY));
        if (configuredPath == null) configuredPath = trimToNull(System.getenv(CONFIG_ENV));
        Path path;
        if (configuredPath != null) {
            path = Path.of(configuredPath);
            if (!Files.isRegularFile(path)) throw new IOException("飞书配置文件不存在: " + path);
        } else {
            path = null;
            for (Path directory = Path.of("").toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
                Path candidate = directory.resolve("config/feishu.local.properties");
                if (Files.isRegularFile(candidate)) { path = candidate; break; }
            }
        }
        if (path == null) throw new IOException("找不到飞书配置：设置 " + APP_ID_ENV + "/" + APP_SECRET_ENV + "，或创建 config/feishu.local.properties");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) { props.load(in); }
        return new AppCredentials(
            firstNonBlank(props.getProperty("feishu.app.id"), props.getProperty("app.id")),
            firstNonBlank(props.getProperty("feishu.app.secret"), props.getProperty("app.secret")));
    }

    /** 仅用于离线测试：从明确路径读取凭证，避免依赖进程环境变量。 */
    static AppCredentials loadFromPathForTest(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) { props.load(in); }
        return new AppCredentials(
            firstNonBlank(props.getProperty("feishu.app.id"), props.getProperty("app.id")),
            firstNonBlank(props.getProperty("feishu.app.secret"), props.getProperty("app.secret")));
    }

    private static String firstNonBlank(String first, String second) {
        String value = trimToNull(first);
        return value != null ? value : trimToNull(second);
    }
    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少飞书配置: " + name);
    }
}