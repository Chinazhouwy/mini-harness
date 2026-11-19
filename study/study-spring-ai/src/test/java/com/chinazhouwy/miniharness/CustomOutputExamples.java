package com.chinazhouwy.miniharness;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

/** 两种独立扩展方式，仅供学习，不改变 Spring AI 的全局 Schema 生成器。 */
final class CustomOutputExamples {

    // record 只声明两个数据字段；这里没有通过构造器验证时长，范围由下方 Schema 扩展表达。
    record Task(String name, int minutes) {}

    /** 方式一：修改转换器生成的 Schema，JSON 解析仍复用父类。 */
    static class TaskConverter extends BeanOutputConverter<Task> {
        TaskConverter() { super(Task.class); }

        @Override
        protected String generateSchema() {
            // 父类构造期间就会调用此方法，不要依赖子类尚未初始化的字段。
            ObjectNode schema = (ObjectNode) JsonMapper.builder().build().readTree(super.generateSchema());
            ObjectNode minutes = (ObjectNode) schema.path("properties").path("minutes");
            // minimum/maximum 是 JSON Schema 约束，不会自动变成 Java 字段的运行时校验。
            minutes.put("minimum", 1);
            minutes.put("maximum", 180);
            return schema.toPrettyString();
        }
    }

    // 自定义注解必须运行时可见，Schema resolver 才能通过反射读取；框架并不自动认识 NumberRange。
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
    @interface NumberRange {
        int min();
        int max();
    }

    record AnnotatedTask(String name, @NumberRange(min = 1, max = 180) int minutes) {}

    /** 方式二：自己配置 victools，把自定义注解翻译成 JSON Schema 关键字。 */
    static String generateSchema(Class<?> type) {
        // DRAFT_2020_12 指定 Schema 方言；PLAIN_JSON 是生成策略集合，不是模型提供商选项。
        var config = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                // 让生成器扫描 record 的非 public 实例字段，否则不能靠传统 JavaBean getter 方式读取这些组件。
                .with(Option.NONPUBLIC_NONSTATIC_FIELDS_WITHOUT_GETTERS);
        config.forFields()
                // 本例让所扫描字段全部 required；是否允许 null 与字段是否必须出现是不同约束。
                .withRequiredCheck(field -> true)
                .withNumberInclusiveMinimumResolver(field -> {
                    NumberRange range = field.getAnnotation(NumberRange.class);
                    return range == null ? null : BigDecimal.valueOf(range.min());
                })
                .withNumberInclusiveMaximumResolver(field -> {
                    NumberRange range = field.getAnnotation(NumberRange.class);
                    return range == null ? null : BigDecimal.valueOf(range.max());
                });
        return new SchemaGenerator(config.build()).generateSchema(type).toPrettyString();
    }
}
