package com.dark.javaHarness.tool.builtin;

import com.dark.javaHarness.contract.api.FileArtifactStore;
import com.dark.javaHarness.tool.trace.ToolCallTracer;
import io.agentscope.runtime.sandbox.box.Sandbox;
import io.agentscope.runtime.sandbox.tools.base.RunShellCommandTool;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * {@code send_file} 工具：把沙箱里的产物读出来放进 {@link FileArtifactStore}，
 * 由渠道在发回复时上传（飞书 {@code msg_type=file}）。
 *
 * <p><b>为什么字节要经服务端中转</b>：文件住在容器里，容器不挂宿主卷，渠道又只认识 HTTP——
 * 要么服务端把它读出来，要么这件事做不成。读取走 {@code stat} + {@code base64}，
 * 只读不写，边界与写文件同一套（越界读同样会泄漏工作区外的内容）。
 *
 * <p>大小上限不是「防小人」而是「防自己」：一个 200MB 的模型权重也能被 {@code send_file} 点名，
 * 内存里放一份、base64 再翻 1.33 倍，进程会被自己拖死。超限直接告知模型，让它先压缩或拆分。
 */
final class SendFileTool {

    private static final Logger log = LoggerFactory.getLogger(SendFileTool.class);

    /** 入参 schema：沙箱工具统一用 JSON Schema 字符串（与 agentscope 工具同口径） */
    private static final String SCHEMA = """
            {"type":"object","properties":{
              "path":{"type":"string","description":"沙箱内的文件路径（相对 /workspace 或绝对路径）"},
              "name":{"type":"string","description":"发送时的文件名；默认取路径里的文件名"}},
             "required":["path"]}
            """;

    private SendFileTool() {
    }

    /** 执行容器内命令的能力；抽成函数式接口，让本类的逻辑不依赖真实沙箱也能测 */
    @FunctionalInterface
    interface Shell {
        String run(String command);
    }

    /**
     * 构建工具回调。
     *
     * @param maxBytes 单文件上限（字节）：超过就拒绝，不截断——半个文件比没有更误导
     */
    static ToolCallback sendFile(Sandbox sandbox, FileArtifactStore store, long maxBytes) {
        RunShellCommandTool shell = new RunShellCommandTool();
        shell.bind(sandbox);
        return callback(shell::run_shell_command, store, maxBytes);
    }

    /** 逻辑入口（包内可见：单测注入假 Shell，不碰容器） */
    static ToolCallback callback(Shell shell, FileArtifactStore store, long maxBytes) {
        return FunctionToolCallback
                .builder("send_file", (Map<String, Object> args, ToolContext context) ->
                        run(shell, store, maxBytes, args, turnId(context)))
                .description("把沙箱里生成的文件（报告/代码/表格）作为附件发给用户。"
                        + "只在用户需要拿到文件本身时调用；调用后不要在正文里重复文件内容")
                .inputSchema(SCHEMA)
                .inputType(Map.class)
                .toolMetadata(ToolMetadata.builder().returnDirect(false).build())
                .build();
    }

    private static String run(Shell shell, FileArtifactStore store, long maxBytes,
                              Map<String, Object> args, String turnId) {
        String absolute;
        try {
            absolute = SandboxGuards.requireWorkspacePath(SandboxToolSupport.str(args, "path"));
        } catch (IllegalArgumentException e) {
            return "路径不可用：" + e.getMessage();
        }
        if (turnId == null) {
            return "无法关联本轮回复，未发送文件。";
        }
        if (absolute.contains("'")) {
            // 路径要拼进 shell 命令：单引号内部无法再转义，直接拒绝（换个文件名即可）
            return "路径不能包含单引号，请先重命名文件再发送：" + absolute;
        }
        long size = parseSize(shell.run("stat -c %s '" + absolute + "'"));
        if (size < 0) {
            return "读不到文件（不存在或不是普通文件）：" + absolute;
        }
        if (size == 0) {
            return "文件为空，未发送：" + absolute;
        }
        if (size > maxBytes) {
            return "文件 " + size + " 字节，超过上限 " + maxBytes + " 字节；"
                    + "请先压缩或拆分成多个小文件再发送。";
        }

        String encoded = shell.run("base64 -w0 '" + absolute + "'");
        byte[] content;
        try {
            // MIME 解码器容忍换行：不同镜像的 base64 有的按 76 列折行
            content = Base64.getMimeDecoder().decode(encoded == null ? "" : encoded.trim());
        } catch (IllegalArgumentException e) {
            return "文件内容读取失败（base64 解析异常）：" + e.getMessage();
        }
        if (content.length == 0) {
            return "文件内容为空，未发送：" + absolute;
        }

        String fileName = fileName(args, absolute);
        store.put(turnId, fileName, content);
        log.info("[sandbox] send_file 已登记 fileName={} bytes={} path={}", fileName, content.length, absolute);
        return "已附上 " + fileName + "（" + content.length + " 字节），渠道会在本轮回复时发送；"
                + "不要在正文里重复文件内容。";
    }

    /** 文件名：优先入参 name，否则取路径末段；去掉会破坏 Content-Disposition 的字符 */
    static String fileName(Map<String, Object> args, String absolute) {
        String raw = SandboxToolSupport.str(args, "name");
        if (raw == null || raw.isBlank()) {
            int slash = absolute.lastIndexOf('/');
            raw = slash >= 0 ? absolute.substring(slash + 1) : absolute;
        }
        return raw.trim().replace("\"", "").replace("\r", "").replace("\n", "");
    }

    /** {@code stat -c %s} 的输出可能是纯数字，也可能带报错文本；非数字按「读不到」处理 */
    static long parseSize(String output) {
        if (output == null) {
            return -1;
        }
        String trimmed = output.trim();
        if (!trimmed.matches("\\d+")) {
            return -1;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String turnId(ToolContext context) {
        if (context == null) {
            return null;
        }
        Object value = context.getContext().get(ToolCallTracer.CTX_TURN_ID);
        return value == null ? null : String.valueOf(value);
    }
}
