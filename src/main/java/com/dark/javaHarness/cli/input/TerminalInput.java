package com.dark.javaHarness.cli.input;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.jline.terminal.Attributes;

/**
 * CLI 终端输入层（自包含，从 ChatCli 拆出）：真实终端下由 JLine 3 接管——
 * 上下键翻阅输入历史（持久化到用户目录）、{@code /} 命令自动补全菜单、多行粘贴；
 * 无 TTY 环境（如 exec:java 内嵌 JVM）或终端初始化失败自动降级为行式读取。
 *
 * <p>入口 {@link #open()}：优先 JLine 终端（真实 TTY），dumb 终端降级。
 * UI 输出经 {@link WriterBridge} 桥到 terminal.writer()（jansi 宽字符通道
 * WriteConsoleW，不受控制台代码页影响），中文与 ✓/⏺ 永不乱码。
 */
public final class TerminalInput {

    private TerminalInput() {
    }

    /** 行输入源抽象：read() 返回 null 表示 EOF；out() 为配套 UI 输出通道 */
    public interface LineInput {
        String read();

        /** 配套输出通道：JLine 实现返回宽字符桥接流（免代码页乱码）；降级实现走 stdout */
        default PrintStream out() {
            return System.out;
        }

        default void shutdown() {
        }
    }

    /**
     * 打开输入源：真实 TTY 走 JLine（历史/补全/粘贴），全程 cbreak 模式——
     * 回合输出期间抢跑按键静默滞留，不被 cooked 行规程回显污染屏幕；
     * dumb 终端（无键盘接管能力，如 exec:java 内嵌 JVM）降级行式读取。
     */
    public static LineInput open() {
        try {
            org.jline.terminal.Terminal terminal = org.jline.terminal.TerminalBuilder.terminal();
            if (terminal.getType().contains("dumb")) {
                terminal.close();
                System.out.println("\033[90m（非交互终端，输入降级：无历史翻阅/补全；"
                        + "用 mvn -Pcli compile exec:exec 可获完整体验）\033[0m");
                return legacy(System.in, System.out);
            }
            Attributes original = terminal.getAttributes();
            enterCbreak(terminal);
            // JVM 退出兜底（输出期间 Ctrl+C 直接杀进程，不走 shutdown()）：恢复终端属性防外壳不回显
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> restoreQuietly(terminal, original), "terminal-cbreak-restore"));
            return new JLineInput(terminal, original);
        } catch (Exception e) {
            System.out.println("\033[90m（终端初始化失败，输入降级: " + e.getMessage() + "）\033[0m");
            return legacy(System.in, System.out);
        }
    }

    /**
     * cbreak（半 raw）模式：关 ICANON/ECHO/ICRNL/IXON，保留 OPOST/ONLCR/ISIG。
     * 修复「抢跑回显污染」：readLine 之外（回合输出期间）终端处于 cooked 模式，用户抢跑按键
     * 会被 tty 行规程立即回显——混入程序输出行（成为历史滚动内容，任何重绘都擦不掉）且字节滞留
     * 输入队列；cbreak 下按键静默滞留，prompt 出现后由 JLine 统一读入 buffer（可编辑可删）。
     * 保留 OPOST/ONLCR：程序输出 \n 照常转 \r\n（标准 raw 会关掉它导致阶梯排版）；
     * 保留 ISIG：输出期间 Ctrl+C 语义不变（SIGINT → JVM 退出 → hook 恢复终端）；
     * 关 ICRNL：\r 原样进 JLine（readLine 标准 accept 路径）；关 IXON：Ctrl+S/Q 不冻结输出。
     * 与 readLine 内部的 enterRawMode 嵌套安全：readLine 退出时恢复到进入时的 cbreak。
     */
    static void enterCbreak(org.jline.terminal.Terminal terminal) throws java.io.IOException {
        Attributes cbreak = new Attributes(terminal.getAttributes());
        cbreak.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        cbreak.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        cbreak.setInputFlag(Attributes.InputFlag.ICRNL, false);
        cbreak.setInputFlag(Attributes.InputFlag.IXON, false);
        terminal.setAttributes(cbreak);
    }

    /** 恢复终端属性（shutdown 与 JVM hook 共用；终端已关/重复恢复吞异常） */
    static void restoreQuietly(org.jline.terminal.Terminal terminal, Attributes original) {
        try {
            terminal.setAttributes(original);
        } catch (Exception ignored) {
            // 终端已关闭或已恢复
        }
    }

    /** 降级输入：标准行式读取（无历史/补全，但任何环境可用）；包可见供降级路径单测直连 */
    static LineInput legacy(InputStream in, PrintStream out) {
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        return new LineInput() {
            @Override
            public String read() {
                try {
                    out.print("你> ");
                    out.flush();
                    return br.readLine();
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public PrintStream out() {
                return out;
            }
        };
    }

    /** JLine 行读取：上下键历史（持久化）+ `/` 命令补全 + 多行粘贴（bracketed paste） */
    private record JLineInput(org.jline.terminal.Terminal terminal,
                              Attributes originalAttributes,
                              org.jline.reader.LineReader reader) implements LineInput {

        JLineInput(org.jline.terminal.Terminal terminal, Attributes originalAttributes) {
            this(terminal, originalAttributes, org.jline.reader.LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(commandCompleter())
                    // 历史持久化到用户目录：跨进程保留，↑↓ 可翻阅
                    .variable(org.jline.reader.LineReader.HISTORY_FILE,
                            Path.of(System.getProperty("user.home"), ".javaHarness_history"))
                    .build());
        }

        @Override
        public String read() {
            try {
                return reader.readLine("你> ");
            } catch (org.jline.reader.EndOfFileException e) {
                return null; // Ctrl+D / 流关闭
            } catch (org.jline.reader.UserInterruptException e) {
                return ""; // Ctrl+C：清空当前行继续
            }
        }

        /**
         * UI 输出经 {@link WriterBridge} 桥到 terminal.writer()：宽字符通道（WriteConsoleW），
         * 与控制台代码页（GBK/65001）无关，中文与 ✓/⏺ 永不乱码。
         * ⚠️ 不能用 terminal.output()：那是 jansi 字节通道，ANSI 序列被翻译但普通文本字节
         * 直传控制台按代码页解读——UTF-8 中文在 GBK 代码页必乱（LineReader 的提示符正常
         * 正是因为它走 writer()）。
         */
        @Override
        public PrintStream out() {
            return new PrintStream(new WriterBridge(terminal.writer()), true,
                    StandardCharsets.UTF_8);
        }

        @Override
        public void shutdown() {
            terminal.writer().flush();
            restoreQuietly(terminal, originalAttributes); // 恢复 cooked；与 JVM hook 重复恢复被吞
        }

        /** `/` 命令补全：根命令直接列出，/agent 的参数补全 off */
        private static org.jline.reader.Completer commandCompleter() {
            return (reader, line, candidates) -> {
                String buffer = line.toString();
                String word = line.word().toString();
                if (buffer.stripLeading().startsWith("/agent")) {
                    if ("off".startsWith(word)) {
                        candidates.add(new org.jline.reader.Candidate("off"));
                    }
                    return;
                }
                if (!word.startsWith("/")) {
                    return;
                }
                for (String cmd : new String[]{"/help", "/new", "/agent", "/resume", "/provider", "/exit", "/quit"}) {
                    if (cmd.startsWith(word)) {
                        candidates.add(new org.jline.reader.Candidate(cmd));
                    }
                }
            };
        }
    }

    /**
     * PrintStream → Writer 桥：把 UTF-8 字节流经 CharsetDecoder 解码成字符，写入 JLine writer
     * （jansi 宽字符通道 WriteConsoleW，与控制台代码页无关）。
     * 不完整的多字节尾字符由 decoder 状态机保留，跨 write 调用安全。
     */
    private static final class WriterBridge extends java.io.OutputStream {

        private final java.io.Writer writer;
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        private ByteBuffer in = ByteBuffer.allocate(1024);

        WriterBridge(java.io.Writer writer) {
            this.writer = writer;
        }

        @Override
        public synchronized void write(int b) {
            if (!in.hasRemaining()) {
                grow(in.capacity());
            }
            in.put((byte) b);
            drain();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (len > in.remaining()) {
                grow(len);
            }
            in.put(b, off, len);
            drain();
        }

        /** 扩容到能容纳 need 字节（保留已缓冲内容） */
        private void grow(int need) {
            ByteBuffer nio = ByteBuffer
                    .allocate(Math.max(in.capacity() * 2, in.position() + need));
            in.flip();
            nio.put(in);
            in = nio;
        }

        /** 解码缓冲中所有完整字符；不完整多字节尾留 decoder（compact 后待后续补齐） */
        private void drain() {
            if (in.position() == 0) {
                return;
            }
            in.flip();
            while (in.hasRemaining()) {
                CharBuffer out = CharBuffer.allocate(Math.max(16, in.remaining()));
                decoder.decode(in, out, false);
                out.flip();
                if (out.hasRemaining()) {
                    char[] chars = new char[out.remaining()];
                    out.get(chars);
                    try {
                        writer.write(chars);
                    } catch (IOException e) {
                        // 终端已不可写：输出静默丢弃（PrintStream 语义同为吞错）
                    }
                }
            }
            in.compact();
        }

        @Override
        public void flush() {
            try {
                writer.flush();
            } catch (IOException ignored) {
                // 同上
            }
        }

        @Override
        public void close() {
            flush();
        }
    }
}
