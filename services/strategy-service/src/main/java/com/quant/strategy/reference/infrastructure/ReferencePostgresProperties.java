package com.quant.strategy.reference.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reference PostgreSQL 存储配置。
 */
@ConfigurationProperties(prefix = "reference.postgres")
public class ReferencePostgresProperties {

    private String url = "jdbc:postgresql://localhost:5432/harness_db";
    private String username = "harness";
    private String password = "harness123";

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
