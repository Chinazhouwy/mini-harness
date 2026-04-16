package com.quant.strategy.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

public abstract class AbstractClickHouseIntegrationTest {

    static {
        if (System.getProperty("testcontainers.ryuk.disabled") == null) {
            System.setProperty("testcontainers.ryuk.disabled", "true");
        }
    }

    private static final DockerImageName CLICKHOUSE_IMAGE =
            DockerImageName.parse("clickhouse/clickhouse-server:24.3-alpine");

    private static final String DEFAULT_URL = "jdbc:clickhouse://localhost:8123/harness_db";
    private static final String DEFAULT_USERNAME = "default";
    private static final String DEFAULT_PASSWORD = "harness123";
    private static final String DEFAULT_DB = "harness_db";

    private static final String EXTERNAL_URL = firstNonBlank(
            System.getProperty("CLICKHOUSE_URL"),
            System.getenv("CLICKHOUSE_URL")
    );
    private static final String EXTERNAL_USERNAME = firstNonBlank(
            System.getProperty("CLICKHOUSE_USERNAME"),
            System.getenv("CLICKHOUSE_USERNAME"),
            DEFAULT_USERNAME
    );
    private static final String EXTERNAL_PASSWORD = firstNonBlank(
            System.getProperty("CLICKHOUSE_PASSWORD"),
            System.getenv("CLICKHOUSE_PASSWORD"),
            DEFAULT_PASSWORD
    );

    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(CLICKHOUSE_IMAGE)
            .withEnv("CLICKHOUSE_USER", DEFAULT_USERNAME)
            .withEnv("CLICKHOUSE_PASSWORD", DEFAULT_PASSWORD)
            .withEnv("CLICKHOUSE_DB", "harness_db")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2));

    static {
        if (EXTERNAL_URL == null) {
            CLICKHOUSE.start();
        }
    }

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        if (EXTERNAL_URL != null) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> EXTERNAL_USERNAME);
            registry.add("spring.datasource.password", () -> EXTERNAL_PASSWORD);
        } else {
            String jdbcUrl = String.format(
                    "jdbc:clickhouse://%s:%d/%s",
                    CLICKHOUSE.getHost(),
                    CLICKHOUSE.getMappedPort(8123),
                    DEFAULT_DB
            );
            registry.add("spring.datasource.url", () -> jdbcUrl);
            registry.add("spring.datasource.username", () -> DEFAULT_USERNAME);
            registry.add("spring.datasource.password", () -> DEFAULT_PASSWORD);
        }
        registry.add("spring.datasource.driver-class-name", () -> "com.clickhouse.jdbc.ClickHouseDriver");
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
