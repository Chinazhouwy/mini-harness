package com.chinazhouwy.feishubot.bot;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

final class AgentWorkspace {
    private AgentWorkspace() {}

    /** 读取随应用打包的 Markdown，把有内容的文件加入提示词。 */
    static String systemPrompt(String directory) {
        var prompt = new StringJoiner("\n\n");
        for (String name : List.of("AGENTS.md", "SOUL.md", "USER.md", "MEMORY.md")) {
            var file = new ClassPathResource(directory + "/" + name);
            try {
                String content = file.getContentAsString(StandardCharsets.UTF_8).strip();
                if (!content.isEmpty()) {
                    prompt.add("## " + name + "\n" + content);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("读取 Agent 文件失败 " + file.getPath(), e);
            }
        }
        return prompt.toString();
    }
}
