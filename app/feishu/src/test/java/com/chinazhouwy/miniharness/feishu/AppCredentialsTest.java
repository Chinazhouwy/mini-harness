package com.chinazhouwy.miniharness.feishu;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppCredentialsTest {
    @Test void readsLocalIgnoredConfig() throws Exception {
        Path file = Files.createTempFile("feishu-config", ".properties");
        Files.writeString(file, "feishu.app.id=cli_test\nfeishu.app.secret=secret_test\n");
        AppCredentials credentials = AppCredentials.loadFromPathForTest(file);
        assertEquals("cli_test", credentials.appId());
        assertEquals("secret_test", credentials.appSecret());
    }

    @Test void rejectsBlankCredential() {
        assertThrows(IllegalArgumentException.class, () -> new AppCredentials("cli_test", "  "));
    }
}
