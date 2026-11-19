package com.chinazhouwy.miniharness.feishu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 JSONL 按会话隔离、按上限截断，并能跨实例读回。 */
class ChatHistoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendReadTrimAndIsolateChats() throws Exception {
        ChatHistoryStore store = new ChatHistoryStore(tempDir.toString(), 4);
        store.init();

        store.appendUser("chat-A", "第一条");
        store.appendAssistant("chat-A", "回复一");
        store.appendUser("chat-A", "第二条");
        store.appendAssistant("chat-A", "回复二");
        store.appendUser("chat-A", "第三条");
        store.appendAssistant("chat-A", "回复三");
        store.appendUser("chat-B", "另一个会话");

        List<ChatHistoryStore.ChatMessage> a = store.read("chat-A");
        assertThat(a).hasSize(4);
        assertThat(a.get(0).text()).isEqualTo("第二条");
        assertThat(a.get(3).text()).isEqualTo("回复三");

        ChatHistoryStore restarted = new ChatHistoryStore(tempDir.toString(), 4);
        restarted.init();
        assertThat(restarted.read("chat-A")).extracting(ChatHistoryStore.ChatMessage::text)
                .containsExactly("第二条", "回复二", "第三条", "回复三");
        assertThat(restarted.read("chat-B")).extracting(ChatHistoryStore.ChatMessage::text)
                .containsExactly("另一个会话");

        assertThat(Files.readAllLines(tempDir.resolve("chat-A.jsonl"))).hasSize(4);
        assertThat(Files.readAllLines(tempDir.resolve("chat-B.jsonl"))).hasSize(1);
    }
}
