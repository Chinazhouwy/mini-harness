package com.quant.strategy.infrastructure.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

/**
 * ClickHouse数据库配置
 */
@Configuration
@ConfigurationProperties(prefix = "spring.datasource")
public class DatabaseConfig {
    
    private String url;
    private String username;
    private String password;
    
    /**
     * ClickHouse数据源配置
     */
    @Bean
    public DataSource clickHouseDataSource() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username != null ? username : "default");
        properties.setProperty("password", password != null ? password : "");
        
        // ClickHouse连接配置
        properties.setProperty("socket_timeout", "30000"); // 30秒socket超时
        properties.setProperty("connect_timeout", "5000"); // 5秒连接超时
        properties.setProperty("query_timeout", "60000"); // 60秒查询超时
        properties.setProperty("compress", "true"); // 启用压缩
        properties.setProperty("use_server_time_zone", "true"); // 使用服务器时区
        
        return new ClickHouseDataSource(url, properties);
    }
    
    /**
     * JdbcTemplate配置
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource clickHouseDataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(clickHouseDataSource);
        
        // 配置JdbcTemplate
        jdbcTemplate.setQueryTimeout(60); // 60秒查询超时
        jdbcTemplate.setFetchSize(1000); // 每次获取1000条记录
        jdbcTemplate.setMaxRows(100000); // 最大返回行数
        
        return jdbcTemplate;
    }
    
    /**
     * NamedParameterJdbcTemplate配置
     */
    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource clickHouseDataSource) {
        return new NamedParameterJdbcTemplate(clickHouseDataSource);
    }
    
    // Getter和Setter用于ConfigurationProperties
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