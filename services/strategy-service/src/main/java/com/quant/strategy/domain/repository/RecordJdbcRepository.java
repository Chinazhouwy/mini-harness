package com.quant.strategy.domain.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 专门用于Java Record的JDBC Repository基类
 * 提供通用的单表增删改查操作
 *
 * @param <T> Record类型
 * @param <ID> 主键类型
 */
public abstract class RecordJdbcRepository<T extends Record, ID> {
    
    protected final NamedParameterJdbcTemplate jdbcTemplate;
    protected final Class<T> recordClass;
    protected final String tableName;
    protected final String schemaName;
    protected final ObjectMapper objectMapper;
    
    public RecordJdbcRepository(NamedParameterJdbcTemplate jdbcTemplate, String tableName, String schemaName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
        this.schemaName = schemaName;
        
        // 获取Record类
        this.recordClass = getRecordClass();
        
        // 配置ObjectMapper支持Java 8时间类型
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * 获取Record类的泛型类型
     */
    @SuppressWarnings("unchecked")
    private Class<T> getRecordClass() {
        // 通过反射获取泛型类型
        java.lang.reflect.Type type = getClass().getGenericSuperclass();
        if (type instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) type;
            return (Class<T>) paramType.getActualTypeArguments()[0];
        }
        throw new IllegalStateException("Cannot determine record class type");
    }
    
    /**
     * 获取完整的表名（包含schema）
     */
    protected String getFullTableName() {
        return (schemaName != null && !schemaName.isEmpty()) 
                ? schemaName + "." + tableName 
                : tableName;
    }
    
    /**
     * 保存Record（插入或更新）
     */
    public T save(T record) {
        if (isNew(record)) {
            return insert(record);
        } else {
            update(record);
            return record;
        }
    }
    
    /**
     * 批量保存Record
     */
    public List<T> saveAll(List<T> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<T> savedRecords = new ArrayList<>();
        for (T record : records) {
            savedRecords.add(save(record));
        }
        return savedRecords;
    }
    
    /**
     * 插入Record
     */
    protected T insert(T record) {
        Map<String, Object> paramMap = recordToParamMap(record);
        String insertSql = buildInsertSql(record, paramMap);
        
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(insertSql, new MapSqlParameterSource(paramMap), keyHolder);
        
        // 设置生成的主键（如果适用）
        T updatedRecord = setGeneratedId(record, keyHolder);
        return updatedRecord != null ? updatedRecord : record;
    }
    
    /**
     * 更新Record
     */
    protected void update(T record) {
        Map<String, Object> paramMap = recordToParamMap(record);
        String updateSql = buildUpdateSql(record, paramMap);
        
        jdbcTemplate.update(updateSql, new MapSqlParameterSource(paramMap));
    }
    
    /**
     * 根据ID查找Record
     */
    public Optional<T> findById(ID id) {
        String sql = String.format("SELECT * FROM %s WHERE %s = :id", 
                getFullTableName(), getIdColumnName());
        
        Map<String, Object> params = Collections.singletonMap("id", id);
        
        try {
            List<T> results = jdbcTemplate.query(sql, params, createRowMapper());
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
    
    /**
     * 查找所有Record
     */
    public List<T> findAll() {
        String sql = String.format("SELECT * FROM %s", getFullTableName());
        return jdbcTemplate.query(sql, createRowMapper());
    }
    
    /**
     * 根据ID删除Record
     */
    public void deleteById(ID id) {
        String sql = String.format("DELETE FROM %s WHERE %s = :id", 
                getFullTableName(), getIdColumnName());
        
        Map<String, Object> params = Collections.singletonMap("id", id);
        jdbcTemplate.update(sql, params);
    }
    
    /**
     * 删除Record
     */
    public void delete(T record) {
        ID id = getId(record);
        if (id != null) {
            deleteById(id);
        }
    }
    
    /**
     * 批量删除
     */
    public void deleteAll(List<T> records) {
        for (T record : records) {
            delete(record);
        }
    }
    
    /**
     * 统计Record数量
     */
    public long count() {
        String sql = String.format("SELECT COUNT(*) FROM %s", getFullTableName());
        return jdbcTemplate.queryForObject(sql, Collections.emptyMap(), Long.class);
    }
    
    /**
     * 判断ID是否存在
     */
    public boolean existsById(ID id) {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s = :id", 
                getFullTableName(), getIdColumnName());
        
        Map<String, Object> params = Collections.singletonMap("id", id);
        Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
        return count != null && count > 0;
    }
    
    /**
     * 分页查询
     */
    public List<T> findAll(int page, int size) {
        int offset = page * size;
        String sql = String.format("SELECT * FROM %s ORDER BY %s LIMIT :limit OFFSET :offset", 
                getFullTableName(), getIdColumnName());
        
        Map<String, Object> params = new HashMap<>();
        params.put("limit", size);
        params.put("offset", offset);
        
        return jdbcTemplate.query(sql, params, createRowMapper());
    }
    
    /**
     * 根据条件查询
     */
    public List<T> findByCriteria(Map<String, Object> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return findAll();
        }
        
        StringBuilder sqlBuilder = new StringBuilder("SELECT * FROM ").append(getFullTableName()).append(" WHERE ");
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        
        for (Map.Entry<String, Object> entry : criteria.entrySet()) {
            conditions.add(entry.getKey() + " = :" + entry.getKey());
            params.put(entry.getKey(), entry.getValue());
        }
        
        sqlBuilder.append(String.join(" AND ", conditions));
        return jdbcTemplate.query(sqlBuilder.toString(), params, createRowMapper());
    }
    
    /**
     * 构建INSERT SQL语句
     */
    protected String buildInsertSql(T record, Map<String, Object> paramMap) {
        List<String> columns = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
        
        for (String column : paramMap.keySet()) {
            columns.add(column);
            paramNames.add(":" + column);
        }
        
        return String.format("INSERT INTO %s (%s) VALUES (%s)", 
                getFullTableName(), 
                String.join(", ", columns), 
                String.join(", ", paramNames));
    }
    
    /**
     * 构建UPDATE SQL语句
     */
    protected String buildUpdateSql(T record, Map<String, Object> paramMap) {
        List<String> setClauses = paramMap.keySet().stream()
                .filter(key -> !key.equals(getIdColumnName()))
                .map(key -> key + " = :" + key)
                .collect(Collectors.toList());
        
        return String.format("UPDATE %s SET %s WHERE %s = :%s", 
                getFullTableName(), 
                String.join(", ", setClauses), 
                getIdColumnName(), 
                getIdColumnName());
    }
    
    /**
     * Record转换为参数映射
     */
    protected Map<String, Object> recordToParamMap(T record) {
        Map<String, Object> paramMap = new HashMap<>();
        
        RecordComponent[] components = recordClass.getRecordComponents();
        
        for (RecordComponent component : components) {
            try {
                String methodName = component.getName();
                Method accessor = recordClass.getDeclaredMethod(methodName);
                Object value = accessor.invoke(record);
                
                if (value != null) {
                    // 特殊处理Java 8时间类型
                    if (value instanceof LocalDate) {
                        value = java.sql.Date.valueOf((LocalDate) value);
                    } else if (value instanceof LocalDateTime) {
                        value = java.sql.Timestamp.valueOf((LocalDateTime) value);
                    }
                    
                    // 获取数据库列名（驼峰转下划线）
                    String columnName = camelToSnake(component.getName());
                    paramMap.put(columnName, value);
                }
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException("Failed to access record component: " + component.getName(), e);
            }
        }
        
        return paramMap;
    }
    
    /**
     * 创建RowMapper用于将ResultSet转换为Record
     */
    protected RowMapper<T> createRowMapper() {
        return new RowMapper<T>() {
            @Override
            public T mapRow(ResultSet rs, int rowNum) throws SQLException {
                try {
                    RecordComponent[] components = recordClass.getRecordComponents();
                    Object[] args = new Object[components.length];
                    
                    for (int i = 0; i < components.length; i++) {
                        String columnName = camelToSnake(components[i].getName());
                        
                        Object value = rs.getObject(columnName);
                        
                        // 转换数据库类型到Java类型
                        if (value instanceof java.sql.Date && components[i].getType() == LocalDate.class) {
                            value = ((java.sql.Date) value).toLocalDate();
                        } else if (value instanceof java.sql.Timestamp && components[i].getType() == LocalDateTime.class) {
                            value = ((java.sql.Timestamp) value).toLocalDateTime();
                        } else if (value instanceof java.sql.Time) {
                            // 时间类型暂不处理
                        }
                        
                        args[i] = value;
                    }
                    
                    // 使用Record的规范构造函数
                    return recordClass.getDeclaredConstructor(
                            Arrays.stream(components)
                                    .map(RecordComponent::getType)
                                    .toArray(Class[]::new)
                    ).newInstance(args);
                    
                } catch (Exception e) {
                    throw new SQLException("Failed to create record instance", e);
                }
            }
        };
    }
    
    /**
     * 驼峰命名转下划线命名
     */
    protected String camelToSnake(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
    
    /**
     * 判断Record是否为新增
     */
    protected abstract boolean isNew(T record);
    
    /**
     * 获取Record的ID
     */
    protected abstract ID getId(T record);
    
    /**
     * 设置生成的主键
     * 返回更新后的Record（如果需要创建新实例），否则返回null
     */
    protected abstract T setGeneratedId(T record, KeyHolder keyHolder);
    
    /**
     * 获取ID列名
     */
    protected abstract String getIdColumnName();
}