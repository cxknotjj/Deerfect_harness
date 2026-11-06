package com.dark.javaHarness.tool.builtin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 沙箱工具的服务端边界校验（文件写入路径 / Shell 破坏性命令 / 工具结果长度）。
 *
 * <p>为什么放在服务端：无人值守任务（定时触发、Goal 后台执行）里模型输出没有人复核，
 * 路径越界与破坏性命令不能指望提示词自我约束。调用点在各工具封装内，模型看不到也绕不过。
 *
 * <p><b>这里是减速带，不是主防线</b>。主防线是容器本身不挂载宿主目录——黑名单永远不可能
 * 穷尽 shell 语义（`python -c "os.system(...)"` 就绕过了），它的价值在于拦住模型最常见的
 * 危险写法并留下可审计的拒绝记录。同理，符号链接逃逸不在本类拦截（纯字符串无法判断），
 * 依赖容器隔离兜底；若将来给沙箱挂载宿主卷，必须补容器内 `realpath` 校验。
 */
final class SandboxGuards {

    /** 容器内唯一允许写入的根（agentscope sandbox 的默认工作目录） */
    static final String WORKSPACE = "/workspace";

    /** 工具结果上限：约 1 万 token，超长内容（cat 大文件 / 递归目录）截断后再进上下文 */
    static final int MAX_RESULT_CHARS = 40_000;

    private static final String TRUNCATED_SUFFIX = "\n...[结果已截断，原始长度 %d 字符]";

    /** 破坏性命令模式：命名只为日志可读，匹配即拒绝 */
    private static final List<Rule> DESTRUCTIVE = List.of(
            new Rule("删除根目录或家目录",
                    Pattern.compile("\\brm\\s+(?:-[a-zA-Z]+\\s+)*(?:/|/\\*|~|~/|\\$HOME/?)(?:\\s|$|[;&|])")),
            new Rule("格式化文件系统", Pattern.compile("\\bmkfs(?:\\.[a-z0-9]+)?\\b")),
            new Rule("直接写块设备", Pattern.compile("\\bdd\\b[^\\n]*\\bof=/dev/")),
            new Rule("关机或重启", Pattern.compile("\\b(?:shutdown|reboot|halt|poweroff)\\b")),
            new Rule("fork 炸弹", Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{")),
            new Rule("改根目录权限", Pattern.compile("\\bchmod\\s+(?:-R\\s+)?777\\s+/\\s*(?:$|[;&|])")));

    private SandboxGuards() {
    }

    /**
     * 把模型给的路径解析成容器内的绝对路径，并校验落在 {@link #WORKSPACE} 内。
     *
     * <p>相对路径按工作目录解析（模型的常见写法 `notes/a.md`）；`..` 按 Unix 语义收敛，
     * 根上再 `..` 停在根。`~` 直接拒绝：容器里的家目录不在工作区内，展开成什么取决于
     * 镜像用户，静默替换会让模型以为写到了别处。
     *
     * @return 归一化后的绝对路径（调用方应使用它，而不是原始入参）
     * @throws IllegalArgumentException 路径为空、以 `~` 开头、或归一化后越出工作区
     */
    static String requireWritablePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("写入路径不能为空");
        }
        String path = raw.trim();
        if (path.equals("~") || path.startsWith("~/")) {
            throw new IllegalArgumentException(
                    "不支持 ~ 展开，请使用绝对路径或相对 " + WORKSPACE + " 的路径: " + raw);
        }
        String resolved = resolve(path);
        if (!resolved.equals(WORKSPACE) && !resolved.startsWith(WORKSPACE + "/")) {
            throw new IllegalArgumentException(
                    "写入路径必须在 " + WORKSPACE + " 内，实际解析为 " + resolved + "（原始入参 " + raw + "）");
        }
        return resolved;
    }

    /** 校验 Shell 命令；命中破坏性模式即拒绝。空命令直接拒绝（省一次容器往返） */
    static void requireSafeCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("命令不能为空");
        }
        for (Rule rule : DESTRUCTIVE) {
            if (rule.pattern().matcher(command).find()) {
                throw new IllegalArgumentException(
                        "拒绝执行：" + rule.name() + "（命中服务端安全规则）。"
                                + "沙箱只用于任务内的常规命令，破坏性操作请说明需求由人工确认");
            }
        }
    }

    /** 结果超长截断：保留前缀并注明原始长度，避免整段丢弃后模型误判为空结果 */
    static String truncateResult(String result) {
        if (result == null || result.length() <= MAX_RESULT_CHARS) {
            return result;
        }
        return result.substring(0, MAX_RESULT_CHARS)
                + String.format(TRUNCATED_SUFFIX, result.length());
    }

    /** 纯字符串归一化（Unix 语义：重复斜杠折叠、`.` 丢弃、根上 `..` 停在根） */
    private static String resolve(String path) {
        String absolute = path.startsWith("/") ? path : WORKSPACE + "/" + path;
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : absolute.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
                continue;
            }
            stack.addLast(segment);
        }
        return "/" + String.join("/", stack);
    }

    private record Rule(String name, Pattern pattern) {
    }
}
