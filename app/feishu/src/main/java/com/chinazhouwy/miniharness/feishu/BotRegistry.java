package com.chinazhouwy.miniharness.feishu;

import org.springframework.stereotype.Component;

/** 第一步：花名册里先写死一个 Bot，后续再改成配置或动态创建。 */
@Component
public class BotRegistry {
    public static final Bot MAIN = new Bot(
            "main",
            "小飞",
            "你是飞书里的 AI 助手，叫小飞。用简洁、自然的中文回答用户。"
    );

    public Bot defaultBot() {
        return MAIN;
    }
}
