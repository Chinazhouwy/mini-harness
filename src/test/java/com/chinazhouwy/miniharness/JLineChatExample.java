package com.chinazhouwy.miniharness;

import org.jline.builtins.Completers;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class JLineChatExample {
    public static void main(String[] args) throws Exception {
        // 1. 创建一个终端实例[reference:14]
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        // 2. 创建一个行读取器，它提供了丰富的输入功能[reference:16]
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new AggregateCompleter(new StringsCompleter("help", "list", "exit"), new Completers.FileNameCompleter()))
                .build();

        terminal.writer().println("欢迎！输入 'exit' 结束对话。");
        terminal.flush();

        String line;
        while (true) {
            // 3. 使用 readLine 方法读取用户输入，支持历史、编辑等[reference:18]
            line = reader.readLine("JLine> ");

            if ("exit".equalsIgnoreCase(line)) {
                break;
            }

            // 4. 处理并输出回应
            terminal.writer().println("你输入了: " + line);
            terminal.flush();
        }

        terminal.writer().println("再见！");
        terminal.close();
    }
}
