package com.chinazhouwy.miniharness.config;

import com.chinazhouwy.miniharness.adapter.SpringAiModelPort;
import com.chinazhouwy.miniharness.model.ModelConfig;
import com.chinazhouwy.miniharness.runtime.HarnessEngine;
import com.chinazhouwy.miniharness.runtime.capability.ModelBasedDecisionModel;
import com.chinazhouwy.miniharness.runtime.domain.finance.FinancePackInstaller;
import com.chinazhouwy.miniharness.runtime.evolution.AnalyzerPolicy;
import com.chinazhouwy.miniharness.runtime.evolution.EvalGatePolicy;
import com.chinazhouwy.miniharness.runtime.model.ModelPort;
import com.chinazhouwy.miniharness.runtime.model.NoopModelPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

/**
 * HarnessEngine 装配：唯一的应用级组装点。
 * 模型策略三态——offline 强制离线；auto 有本地配置则接 Spring AI 适配器，否则 Noop。
 * 学习模块（study-*）不参与装配；这里只选 Spring AI 适配器一条实现。
 */
@Configuration
public class HarnessConfig {

    @Value("${miniharness.model.mode:auto}")
    private String modelMode;

    @Bean
    public HarnessEngine harnessEngine() {
        ModelPort modelPort;
        ModelBasedDecisionModel decisionModel = null;
        if ("offline".equalsIgnoreCase(modelMode)) {
            modelPort = new NoopModelPort();
        } else {
            ModelPort springAiPort = tryBuildSpringAiPort();
            if (springAiPort != null) {
                modelPort = springAiPort;
                decisionModel = new ModelBasedDecisionModel(springAiPort);
            } else {
                modelPort = new NoopModelPort();
            }
        }
        HarnessEngine engine = new HarnessEngine(
                new HarnessEngine.EngineConfig(modelPort, decisionModel, 20, 200, 60_000),
                AnalyzerPolicy.financeDefaults(), EvalGatePolicy.defaults());
        FinancePackInstaller.install(engine, "SEED");
        return engine;
    }

    /** 尝试用本地配置构建 Spring AI 模型适配器；配置缺失/异常时返回 null（回退离线模式）。 */
    private ModelPort tryBuildSpringAiPort() {
        try {
            ModelConfig config = ModelConfig.loadProperties();
            if (config.apiKey() == null || config.apiKey().isBlank()
                    || "填写自己的密钥".equals(config.apiKey())) {
                return null;
            }
            com.chinazhouwy.miniharness.model.ModelCreator creator = new com.chinazhouwy.miniharness.model.ModelCreator();
            return new SpringAiModelPort(creator.getModel());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                        .allowedMethods("GET", "POST", "PUT", "DELETE");
            }
        };
    }
}
