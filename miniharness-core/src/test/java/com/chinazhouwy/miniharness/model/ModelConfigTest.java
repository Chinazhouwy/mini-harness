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

class ModelConfigTest {
    @TempDir Path directory;
    private final String originalConfig = System.getProperty("miniharness.config");

    @AfterEach
    void restoreConfigPath() {
        if (originalConfig == null) System.clearProperty("miniharness.config");
        else System.setProperty("miniharness.config", originalConfig);
    }

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

    @Test
    @DisplayName("公共配置：显式指定错误路径时直接报错")
    void rejectsMissingExplicitFile() {
        System.setProperty("miniharness.config", directory.resolve("missing.properties").toString());
        assertThrows(IOException.class, ModelConfig::loadProperties);
    }
}
