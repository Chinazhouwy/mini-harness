package com.chinazhouwy.miniharness.feishu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 飞书消息 content 是 JSON 字符串；SDK 已传递 Gson，这里显式解析避免把 JSON 原文发给模型。 */
public final class FeishuMessageCodec {
    private FeishuMessageCodec() {}

    public static String extractText(String content) {
        if (content == null || content.isBlank()) return "";
        try {
            JsonObject object = JsonParser.parseString(content).getAsJsonObject();
            String text = object.has("text") && object.get("text").isJsonPrimitive()
                    ? object.get("text").getAsString() : "";
            return text == null ? "" : text.trim();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("飞书消息 content 不是合法 JSON", e);
        }
    }

    /** 转义后再交给官方 MessageText/JSON 构造，避免正文里的引号破坏 content JSON。 */
    public static String escapeJsonString(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.toString();
    }
}
