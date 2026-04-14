package com.quant.strategy.lombok;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lombok 功能验证测试
 * 用于验证 Lombok 注解是否正确工作
 */
@Slf4j
class LombokValidationTest {
    
    /**
     * 简单 Lombok 实体类测试
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestEntity {
        private String name;
        private Integer age;
        private Double salary;
    }
    
    @Test
    void testBasicLombokAnnotations() {
        // 测试 Builder 模式
        TestEntity entity = TestEntity.builder()
                .name("John Doe")
                .age(30)
                .salary(50000.0)
                .build();
        
        // 测试 Getter
        assertEquals("John Doe", entity.getName());
        assertEquals(30, entity.getAge());
        assertEquals(50000.0, entity.getSalary());
        
        // 测试 Setter
        entity.setName("Jane Doe");
        entity.setAge(25);
        entity.setSalary(60000.0);
        
        assertEquals("Jane Doe", entity.getName());
        assertEquals(25, entity.getAge());
        assertEquals(60000.0, entity.getSalary());
        
        // 测试 toString
        String toString = entity.toString();
        assertTrue(toString.contains("Jane Doe"));
        assertTrue(toString.contains("25"));
        assertTrue(toString.contains("60000.0"));
        
        // 测试 equals 和 hashCode
        TestEntity entity2 = TestEntity.builder()
                .name("Jane Doe")
                .age(25)
                .salary(60000.0)
                .build();
        
        assertEquals(entity, entity2);
        assertEquals(entity.hashCode(), entity2.hashCode());
        
        log.info("Lombok 基本注解测试通过: {}", entity);
    }
    
    @Test
    void testNoArgsConstructor() {
        TestEntity entity = new TestEntity();
        assertNull(entity.getName());
        assertNull(entity.getAge());
        assertNull(entity.getSalary());
        
        log.info("无参构造函数测试通过");
    }
    
    @Test
    void testAllArgsConstructor() {
        TestEntity entity = new TestEntity("Test", 99, 100000.0);
        assertEquals("Test", entity.getName());
        assertEquals(99, entity.getAge());
        assertEquals(100000.0, entity.getSalary());
        
        log.info("全参构造函数测试通过");
    }
    
    @Test
    void testLogAnnotation() {
        log.debug("Debug 日志测试");
        log.info("Info 日志测试");
        log.warn("Warn 日志测试");
        log.error("Error 日志测试");
        
        // 如果代码能执行到这里，说明 @Slf4j 注解工作正常
        assertTrue(true, "@Slf4j 注解正常工作");
    }
    
    /**
     * 测试链式调用
     */
    @Test
    void testMethodChaining() {
        TestEntity entity = TestEntity.builder()
                .name("Chain Test")
                .age(40)
                .salary(75000.0)
                .build();
        
        // 验证链式调用
        TestEntity result = entity.toBuilder()
                .age(41)
                .build();
        
        assertEquals("Chain Test", result.getName());
        assertEquals(41, result.getAge());
        assertEquals(75000.0, result.getSalary());
        
        log.info("链式调用测试通过: {}", result);
    }
    
    /**
     * 综合测试：模拟 JPA 实体类使用场景
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class JpaLikeEntity {
        private Long id;
        private String entityName;
        private Boolean active;
        
        // 业务方法
        public boolean isActiveEntity() {
            return active != null && active;
        }
    }
    
    @Test
    void testJpaLikeEntity() {
        JpaLikeEntity entity = JpaLikeEntity.builder()
                .id(1L)
                .entityName("Test Entity")
                .active(true)
                .build();
        
        // 测试 getter
        assertEquals(1L, entity.getId());
        assertEquals("Test Entity", entity.getEntityName());
        assertTrue(entity.getActive());
        
        // 测试业务方法
        assertTrue(entity.isActiveEntity());
        
        // 测试 setter
        entity.setActive(false);
        assertFalse(entity.isActiveEntity());
        
        log.info("JPA 风格实体测试通过: {}", entity);
    }
    
    /**
     * 运行所有测试的主方法
     */
    public static void main(String[] args) {
        System.out.println("开始运行 Lombok 验证测试...");
        
        LombokValidationTest test = new LombokValidationTest();
        
        try {
            test.testBasicLombokAnnotations();
            test.testNoArgsConstructor();
            test.testAllArgsConstructor();
            test.testLogAnnotation();
            test.testMethodChaining();
            test.testJpaLikeEntity();
            
            System.out.println("✅ 所有 Lombok 测试通过！");
            System.out.println("Lombok 配置正确，可以正常生成 getter、setter、builder 等方法。");
            
        } catch (Exception e) {
            System.err.println("❌ Lombok 测试失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}