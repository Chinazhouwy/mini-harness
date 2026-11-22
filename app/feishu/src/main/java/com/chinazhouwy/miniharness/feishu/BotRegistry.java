package com.chinazhouwy.miniharness.feishu;

import org.springframework.stereotype.Component;

/** 第一步：花名册里先写死一个 Bot，后续再改成配置或动态创建。 */
@Component
public class BotRegistry {
    public static final Bot MAIN = new Bot("main", "小飞");

    public Bot defaultBot() {
        return MAIN;
    }
}
