package com.chinazhouwy.feishubot.bot;

import com.chinazhouwy.feishubot.ProjectPaths;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Bot 提示词模板和每个私聊的运行时版本。文件保存在工具工作区之外。 */
@Component
public final class AgentWorkspace {
    public static final List<String> FILES = List.of("AGENTS.md", "SOUL.md", "USER.md", "MEMORY.md");
    private final Path directory;

    public AgentWorkspace() {
        this(ProjectPaths.home().resolve("data/feishubot/evolution"));
    }

    AgentWorkspace(Path directory) {
        this.directory = directory;
    }

    String systemPrompt(String botId, String chatId) {
        var prompt = new StringJoiner("\n\n");
        snapshot(botId, chatId).forEach((name, content) -> {
            if (!content.isBlank()) {
                prompt.add("## " + name + "\n" + content.strip());
            }
        });
        return prompt.toString();
    }

    synchronized Map<String, String> snapshot(String botId, String chatId) {
        var files = new LinkedHashMap<String, String>();
        Path runtime = chatId == null ? null : profile(botId, chatId);
        try {
            for (String name : FILES) {
                Path file = runtime == null ? null : runtime.resolve(name);
                if (file != null && Files.exists(file)) {
                    files.put(name, Files.readString(file));
                } else {
                    files.put(name, new ClassPathResource("bots/" + botId + "/" + name)
                            .getContentAsString(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 Agent 工作区失败", e);
        }
        return files;
    }

    synchronized void apply(String botId, String chatId, Map<String, String> current,
                            Map<String, String> changes) throws IOException {
        Path runtime = profile(botId, chatId);
        for (String name : FILES) {
            Path file = runtime.resolve(name);
            if (changes.containsKey(name) || !Files.exists(file)) {
                writeAtomically(file, changes.getOrDefault(name, current.get(name)).strip() + "\n");
            }
        }
    }

    Path pendingDirectory() {
        return directory.resolve("pending");
    }

    private Path profile(String botId, String chatId) {
        return directory.resolve("profiles").resolve(key(botId)).resolve(key(chatId));
    }

    static String key(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path staged = Files.createTempFile(target.getParent(), ".evolution-", ".tmp");
        try {
            Files.writeString(staged, content);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
