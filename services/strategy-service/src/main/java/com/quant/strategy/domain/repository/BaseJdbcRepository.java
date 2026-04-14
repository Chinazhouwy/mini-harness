package com.quant.strategy.domain.repository;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于NamedParameterJdbcTemplate的通用Repository基类
 * 提供通用的单表增删改查操作
 *
 * @param <T> 实体类型
 * @param <ID> 主键类型
 */
public abstract class BaseJdbcRepository<T, ID> {
    
    protected final NamedParameterJdbcTemplate jdbcTemplate;
    protected final Class<T> entityClass;
    protected final String tableName;
    protected final String schemaName;
    
    @SuppressWarnings("unchecked")
    public BaseJdbcRepository(NamedParameterJdbcTemplate jdbcTemplate, String tableName, String schemaName) {
        this.jdbcTemplate = jdbcTemplate;
        this.entityClass = (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass())
                .getActualTypeArguments()[0];
        this.tableName = tableName;
        this.schemaName = schemaName;
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
     * 保存实体（插入或更新）
     */
    public T save(T entity) {
        if (isNew(entity)) {
            return insert(entity);
        } else {
            update(entity);
            return entity;
        }
    }
    
    /**
     * 批量保存实体
     */
    public List<T> saveAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<T> savedEntities = new ArrayList<>();
        for (T entity : entities) {
            savedEntities.add(save(entity));
        }
        return savedEntities;
    }
    
    /**
     * 插入实体
     */
    protected T insert(T entity) {
        Map<String, Object> paramMap = entityToParamMap(entity);
        String insertSql = buildInsertSql(entity);
        
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(insertSql, new MapSqlParameterSource(paramMap), keyHolder);
        
        // 设置生成的主键
        setGeneratedId(entity, keyHolder);
        
        return entity;
    }
    
    /**
     * 更新实体
     */
    protected void update(T entity) {
        Map<String, Object> paramMap = entityToParamMap(entity);
        String updateSql = buildUpdateSql(entity);
        
        jdbcTemplate.update(updateSql, new MapSqlParameterSource(paramMap));
    }
    
    /**
     * 根据ID查找实体
     */
    public Optional<T> findById(ID id) {
        String sql = String.format("SELECT * FROM %s WHERE %s = :id", 
                getFullTableName(), getIdColumnName());
        
        Map<String, Object> params = Collections.singletonMap("id", id);
        
        try {
            T result = jdbcTemplate.queryForObject(sql, params, 
                    new BeanPropertyRowMapper<>(entityClass));
            return Optional.ofNullable(result);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
    
    /**
     * 查找所有实体
     */
    public List<T> findAll() {
        String sql = String.format("SELECT * FROM %s", getFullTableName());
        return jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(entityClass));
    }
    
    /**
     * 根据ID删除实体
     */
    public void deleteById(ID id) {
        String sql = String.format("DELETE FROM %s WHERE %s = :id", 
                getFullTableName(), getIdColumnName());
        
        Map<String, Object> params = Collections.singletonMap("id", id);
        jdbcTemplate.update(sql, params);
    }
    
    /**
     * 删除实体
     */
    public void delete(T entity) {
        ID id = getId(entity);
        if (id != null) {
            deleteById(id);
        }
    }
    
    /**
     * 批量删除
     */
    public void deleteAll(List<T> entities) {
        for (T entity : entities) {
            delete(entity);
        }
    }
    
    /**
     * 统计实体数量
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
        
        return jdbcTemplate.query(sql, params, new BeanPropertyRowMapper<>(entityClass));
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
        return jdbcTemplate.query(sqlBuilder.toString(), params, new BeanPropertyRowMapper<>(entityClass));
    }
    
    /**
     * 构建INSERT SQL语句
     */
    protected String buildInsertSql(T entity) {
        Map<String, Object> paramMap = entityToParamMap(entity);
        
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
    protected String buildUpdateSql(T entity) {
        Map<String, Object> paramMap = entityToParamMap(entity);
        
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
     * 实体对象转换为参数映射
     */
    protected Map<String, Object> entityToParamMap(T entity) {
        Map<String, Object> paramMap = new HashMap<>();
        
        try {
            Field[] fields = entityClass.getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(entity);
                
                if (value != null) {
                    // 特殊处理Java 8时间类型
                    if (value instanceof LocalDate) {
                        value = java.sql.Date.valueOf((LocalDate) value);
                    } else if (value instanceof LocalDateTime) {
                        value = java.sql.Timestamp.valueOf((LocalDateTime) value);
                    }
                    
                    // 获取数据库列名（驼峰转下划线）
                    String columnName = camelToSnake(field.getName());
                    paramMap.put(columnName, value);
                }
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to convert entity to param map", e);
        }
        
        return paramMap;
    }
    
    /**
     * 驼峰命名转下划线命名
     */
    protected String camelToSnake(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
    
    /**
     * 判断实体是否为新增
     */
    protected abstract boolean isNew(T entity);
    
    /**
     * 获取实体的ID
     */
    protected abstract ID getId(T entity);
    
    /**
     * 设置生成的主键
     */
    protected abstract void setGeneratedId(T entity, KeyHolder keyHolder);
    
    /**
     * 获取ID列名
     */
    protected abstract String getIdColumnName();
}