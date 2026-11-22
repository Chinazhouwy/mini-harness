package com.chinazhouwy.feishubot.bot;

import org.springframework.stereotype.Component;

/** 第一步：花名册里先写死一个 Bot，后续再改成配置或动态创建。 */
@Component
public class BotRegistry {
    public static final Bot MAIN = new Bot("main", "小飞");

    public Bot defaultBot() {
        return MAIN;
    }

    public Bot requireBot(String botId) {
        if (!MAIN.id().equals(botId)) {
            throw new IllegalArgumentException("找不到 Bot " + botId);
        }
        return MAIN;
    }
}
