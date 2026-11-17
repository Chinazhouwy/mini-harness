package com.chinazhouwy.miniharness.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 公共配置读取的离线回归。只使用临时假配置；测试会修改 JVM 系统属性并在结束后恢复，不应并行运行这些修改同一属性的用例。 */
class ModelConfigTest {
    @TempDir Path directory;
    private final String originalConfig = System.getProperty("miniharness.config");

    // 每个测试结束后恢复系统属性，避免本例临时路径污染同一 JVM 中其他测试。
    @AfterEach
    void restoreConfigPath() {
        if (originalConfig == null) System.clearProperty("miniharness.config");
        else System.setProperty("miniharness.config", originalConfig);
    }

    /**
     * 验证三个模块共用的模型配置读取。
     * <p>关键点：JVM 属性 miniharness.config 指向临时文件；分别使用新旧键名，兼容逻辑应补全另一套别名。
     * <p>观察：断言读取的模型和密钥来自临时文件；示例值不是实际凭据。
     */
    @Test
    @DisplayName("公共配置：兼容新旧键名，供三个模块分别读取")
    void supportsBothPropertyNames() throws Exception {
        Path file = directory.resolve("model.properties");
        System.setProperty("miniharness.config", file.toString());
        for (String prefix : new String[]{"miniharness", "openrouter"}) {
            Files.writeString(file, prefix + ".api.key=test-key\n" + prefix + ".base.url=https://example.invalid\n"
                    + prefix + ".model=test-model\n");
            ModelConfig config = ModelConfig.loadProperties();
            assertEquals("test-model", config.model());
            assertEquals("test-key", config.apiKey());
            assertEquals("test-model", ModelConfig.loadLocalProperties().getProperty("openrouter.model"));
        }
    }

    /**
     * 验证显式配置路径写错时的行为。
     * <p>关键点：指定 miniharness.config 后，读取失败应直接抛 IOException，不回退到仓库内其他配置。
     * <p>观察：观察缺失文件异常；AfterEach 会恢复原系统属性，避免影响后续测试。
     */
    @Test
    @DisplayName("公共配置：显式指定错误路径时直接报错")
    void rejectsMissingExplicitFile() {
        System.setProperty("miniharness.config", directory.resolve("missing.properties").toString());
        assertThrows(IOException.class, ModelConfig::loadProperties);
    }
}
