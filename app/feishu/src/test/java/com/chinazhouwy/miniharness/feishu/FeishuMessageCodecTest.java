package com.chinazhouwy.miniharness.feishu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeishuMessageCodecTest {
    @Test void readsTextFromFeishuContentJson() {
        assertEquals("你好 @ticket 查 T-1", FeishuMessageCodec.extractText("{\"text\":\"  你好 @ticket 查 T-1 \"}"));
    }
    @Test void escapesTextForMessageContent() {
        assertEquals("a\\\"b\\\\c\\nd", FeishuMessageCodec.escapeJsonString("a\"b\\c\nd"));
    }
}
