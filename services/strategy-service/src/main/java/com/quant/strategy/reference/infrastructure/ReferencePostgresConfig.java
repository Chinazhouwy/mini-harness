package com.quant.strategy.reference.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Reference PostgreSQL 数据源配置。
 *
 * <p>只有 `reference.store.type=jdbc` 时才启用，避免默认开发路径必须启动
 * PostgreSQL。ClickHouse 仍然由原有 `DatabaseConfig` 管理。</p>
 */
@Configuration
@ConditionalOnProperty(name = "reference.store.type", havingValue = "jdbc")
@EnableConfigurationProperties(ReferencePostgresProperties.class)
public class ReferencePostgresConfig {

    @Bean
    public DataSource referencePostgresDataSource(ReferencePostgresProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        return dataSource;
    }

    @Bean
    public NamedParameterJdbcTemplate referenceNamedParameterJdbcTemplate(
        @Qualifier("referencePostgresDataSource") DataSource dataSource
    ) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}
