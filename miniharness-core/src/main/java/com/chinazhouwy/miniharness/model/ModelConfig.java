package com.chinazhouwy.miniharness.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ModelConfig(
        String apiKey,
        String baseUrl,
        String model
) {

    private static ModelConfig from (Properties props) {
        return new ModelConfig(
                props.getProperty("miniharness.api.key"),
                props.getProperty("miniharness.base.url"),
                props.getProperty("miniharness.model")
        );
    }


    public static ModelConfig loadProperties() throws IOException {
        return from(loadLocalProperties());
    }

    /** 三个学习模块共用配置读取，不依赖 Spring，也不把本地凭据打进 JAR。 */
    public static Properties loadLocalProperties() throws IOException {
        Properties props = new Properties();
        String configuredPath = System.getProperty("miniharness.config");
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv("MINIHARNESS_CONFIG");
        }
        if (configuredPath != null && !configuredPath.isBlank()) {
            // 显式指定的文件不存在时直接报错，避免悄悄使用另一份模型配置。
            try (InputStream in = Files.newInputStream(Path.of(configuredPath))) {
                props.load(in);
            }
        } else {
            Path config = findProjectFile("config/openrouter.local.properties");
            try (InputStream in = config != null ? Files.newInputStream(config)
                    : ModelConfig.class.getClassLoader().getResourceAsStream("openrouter.local.properties")) {
                if (in == null) {
                    throw new IOException("找不到 config/openrouter.local.properties；也可设置 MINIHARNESS_CONFIG");
                }
                props.load(in);
            }
        }
        // 兼容早期 AgentScope 示例的键名；两种键均存在时保留用户原值。
        for (String name : new String[]{"api.key", "base.url", "model"}) {
            String current = "miniharness." + name;
            String legacy = "openrouter." + name;
            if (props.getProperty(current) == null && props.getProperty(legacy) != null) {
                props.setProperty(current, props.getProperty(legacy));
            }
            if (props.getProperty(legacy) == null && props.getProperty(current) != null) {
                props.setProperty(legacy, props.getProperty(current));
            }
        }
        return props;
    }

    /** 同时支持从仓库根目录和子模块目录启动示例。 */
    public static Path findProjectFile(String relativePath) {
        for (Path directory = Path.of("").toAbsolutePath().normalize(); directory != null;
             directory = directory.getParent()) {
            Path candidate = directory.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

}
