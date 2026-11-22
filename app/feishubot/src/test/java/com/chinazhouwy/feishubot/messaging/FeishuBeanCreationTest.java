package com.chinazhouwy.feishubot.messaging;

import com.chinazhouwy.feishubot.FeishuBotApplication;
import com.chinazhouwy.feishubot.bot.AiReplyService;
import com.chinazhouwy.feishubot.schedule.BotTaskScheduler;
import com.lark.oapi.Client;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Spring 上下文能装配飞书 Bean，且不触发长连接或访问真实凭证。
 * 这是启动失败 “Could not find a destroy method named close” 的回归测试。
 */
class FeishuBeanCreationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeishuConfig.class));

    @Test
    void applicationScansBotComponents() {
        var beans = new DefaultListableBeanFactory();
        new AnnotatedBeanDefinitionReader(beans).register(FeishuBotApplication.class);

        // 只处理 Bean 定义，验证真实入口的扫描范围，无需建立长连接。
        new ConfigurationClassPostProcessor().postProcessBeanDefinitionRegistry(beans);

        assertThat(beans.getBeanDefinition("feishuBotListener").getBeanClassName())
                .isEqualTo(FeishuBotListener.class.getName());
        assertThat(beans.getBeanDefinition("aiReplyService").getBeanClassName())
                .isEqualTo(AiReplyService.class.getName());
        assertThat(beans.getBeanDefinition("botTaskScheduler").getBeanClassName())
                .isEqualTo(BotTaskScheduler.class.getName());
    }

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
