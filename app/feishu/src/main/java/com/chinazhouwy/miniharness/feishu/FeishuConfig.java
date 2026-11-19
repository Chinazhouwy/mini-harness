package com.chinazhouwy.miniharness.feishu;

import com.lark.oapi.Client;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/** 飞书 SDK 客户端与发送器交给 Spring 管理；密钥只从本机配置读取。 */
@Configuration(proxyBeanMethods = false)
public class FeishuConfig {

    @Bean
    @ConditionalOnMissingBean(AppCredentials.class)
    public AppCredentials appCredentials() throws IOException {
        return AppCredentials.load();
    }

    /**
     * SDK 的 HTTP Client 没有 close() 方法；长连接由 FeishuBotListener.stop() 关闭。
     * 显式禁用销毁方法推断，避免 Spring 校验不存在的 close()。
     */
    @Bean(destroyMethod = "")
    public Client feishuClient(AppCredentials credentials) {
        return Client.newBuilder(credentials.appId(), credentials.appSecret()).build();
    }

    @Bean
    public FeishuSender feishuSender(Client feishuClient) {
        return new FeishuSender(feishuClient);
    }
}
