package com.chinazhouwy.miniharness.feishu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/** 按飞书会话 chatId 把最近若干轮问答保存到本地 JSONL，重启后可以继续读取。 */
@Service
public class ChatHistoryStore {
    private static final Logger log = LoggerFactory.getLogger(ChatHistoryStore.class);
    private static final Pattern SAFE_CHAT_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final Path root;
    private final int maxMessages;

    public ChatHistoryStore(@Value("${feishu.history.dir:logs/feishu-conversations}") String directory,
                            @Value("${feishu.history.max-messages:20}") int maxMessages) {
        this.root = Path.of(directory);
        this.maxMessages = Math.max(2, maxMessages);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(root);
        log.info("飞书会话历史目录={}，每个会话最多保留 {} 条消息", root.toAbsolutePath(), maxMessages);
    }

    public synchronized List<ChatMessage> read(String chatId) {
        Path file = fileFor(chatId);
        if (!Files.isRegularFile(file)) return List.of();
        Deque<ChatMessage> tail = new ArrayDeque<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                ChatMessage message = parse(line);
                if (message != null) {
                    tail.addLast(message);
                    while (tail.size() > maxMessages) tail.removeFirst();
                }
            }
        } catch (IOException e) {
            log.warn("读取会话历史失败 chatId={}：{}", mask(chatId), e.getMessage());
            return List.of();
        }
        return new ArrayList<>(tail);
    }

    public synchronized void appendUser(String chatId, String text) {
        append(chatId, "user", text);
    }

    public synchronized void appendAssistant(String chatId, String text) {
        append(chatId, "assistant", text);
    }

    private void append(String chatId, String role, String text) {
        JsonObject line = new JsonObject();
        line.addProperty("at", Instant.now().toString());
        line.addProperty("role", role);
        line.addProperty("text", text == null ? "" : text);
        try {
            Files.writeString(fileFor(chatId), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            trim(chatId);
        } catch (IOException e) {
            log.warn("写入会话历史失败 chatId={}：{}", mask(chatId), e.getMessage());
        }
    }

    private void trim(String chatId) throws IOException {
        Path file = fileFor(chatId);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() <= maxMessages) return;
        List<String> tail = lines.subList(lines.size() - maxMessages, lines.size());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, tail, StandardCharsets.UTF_8);
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private Path fileFor(String chatId) {
        String safe = SAFE_CHAT_ID.matcher(chatId).matches() ? chatId : hash(chatId);
        return root.resolve(safe + ".jsonl");
    }

    private static String hash(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChatMessage parse(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            JsonObject object = JsonParser.parseString(line).getAsJsonObject();
            String role = object.has("role") ? object.get("role").getAsString() : "";
            String text = object.has("text") ? object.get("text").getAsString() : "";
            if (!"user".equals(role) && !"assistant".equals(role)) return null;
            return new ChatMessage(role, text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String mask(String chatId) {
        return chatId == null || chatId.length() <= 8 ? "***" : chatId.substring(0, 8) + "***";
    }

    public record ChatMessage(String role, String text) {}
}
