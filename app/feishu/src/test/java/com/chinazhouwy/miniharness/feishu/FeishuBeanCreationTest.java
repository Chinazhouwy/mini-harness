package com.chinazhouwy.miniharness.feishu;

import com.lark.oapi.Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Spring 上下文能装配飞书 Bean，且不触发长连接或访问真实凭证。
 * 这是启动失败 “Could not find a destroy method named close” 的回归测试。
 */
class FeishuBeanCreationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeishuConfig.class));

    @Test
    void createsFeishuBeansWithoutNetworkOrRealSecrets() {
        contextRunner.withUserConfiguration(TestCredentialsConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AppCredentials.class).appId()).isEqualTo("cli_test");
            assertThat(context.getBean(Client.class)).isNotNull();
            assertThat(context.getBean(FeishuSender.class)).isNotNull();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestCredentialsConfig {
        @Bean
        AppCredentials testCredentials() {
            return new AppCredentials("cli_test", "secret_test");
        }
    }
}
