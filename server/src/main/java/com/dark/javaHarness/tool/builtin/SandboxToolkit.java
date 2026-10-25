package com.dark.javaHarness.tool.builtin;

import tools.jackson.databind.ObjectMapper;
import io.agentscope.runtime.sandbox.box.Sandbox;
import io.agentscope.runtime.sandbox.tools.SandboxTool;
import io.agentscope.runtime.sandbox.tools.base.RunPythonTool;
import io.agentscope.runtime.sandbox.tools.base.RunShellCommandTool;
import io.agentscope.runtime.sandbox.tools.browser.ClickTool;
import io.agentscope.runtime.sandbox.tools.browser.CloseTool;
import io.agentscope.runtime.sandbox.tools.browser.NavigateTool;
import io.agentscope.runtime.sandbox.tools.browser.PressKeyTool;
import io.agentscope.runtime.sandbox.tools.browser.SnapshotTool;
import io.agentscope.runtime.sandbox.tools.browser.TypeTool;
import io.agentscope.runtime.sandbox.tools.fs.CreateDirectoryTool;
import io.agentscope.runtime.sandbox.tools.fs.DirectoryTreeTool;
import io.agentscope.runtime.sandbox.tools.fs.EditFileTool;
import io.agentscope.runtime.sandbox.tools.fs.GetFileInfoTool;
import io.agentscope.runtime.sandbox.tools.fs.ListDirectoryTool;
import io.agentscope.runtime.sandbox.tools.fs.MoveFileTool;
import io.agentscope.runtime.sandbox.tools.fs.ReadFileTool;
import io.agentscope.runtime.sandbox.tools.fs.ReadMultipleFilesTool;
import io.agentscope.runtime.sandbox.tools.fs.SearchFilesTool;
import io.agentscope.runtime.sandbox.tools.fs.WriteFileTool;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 沙箱工具封装（替代已移除的 spring-ai-alibaba-sandbox ToolkitInit）：
 * 把 agentscope-runtime 的 {@link SandboxTool} 直接包成 Spring AI {@link ToolCallback}。
 *
 * <p>每个工具只做三件事：绑定沙箱、取 agentscope 自带的名称/描述/入参 schema、
 * 把请求参数分派到对应 agentscope 方法；返回值统一包装为
 * {@code {"Response":{"result":...,"message":...}}}（与原 SAA 封装输出一致，
 * 模型看到的工具名/描述/入参 schema 与结果结构均不变）。
 */
public final class SandboxToolkit {

    private static final Logger log = LoggerFactory.getLogger(SandboxToolkit.class);

    private SandboxToolkit() {
    }

    /* ---------------- 执行类（base 容器） ---------------- */

    /** Python 代码执行（run_ipython_cell） */
    public static ToolCallback runPythonCode(Sandbox sandbox) {
        RunPythonTool tool = new RunPythonTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.run_ipython_cell(str(args, "code")),
                "Code execution completed");
    }

    /** Shell 命令执行（run_shell_command） */
    public static ToolCallback runShellCommand(Sandbox sandbox) {
        RunShellCommandTool tool = new RunShellCommandTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.run_shell_command(str(args, "command")),
                "Shell execution completed");
    }

    /* ---------------- 只读文件类（base 容器） ---------------- */

    /** 读单个文件 */
    public static ToolCallback readFile(Sandbox sandbox) {
        ReadFileTool tool = new ReadFileTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.fs_read_file(str(args, "path")),
                "Filesystem read_file completed");
    }

    /** 批量读文件 */
    public static ToolCallback readMultipleFiles(Sandbox sandbox) {
        ReadMultipleFilesTool tool = new ReadMultipleFilesTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.fs_read_multiple_files(strArray(args, "paths")),
                "Filesystem read_multiple_files completed");
    }

    /** 列目录 */
    public static ToolCallback listDirectory(Sandbox sandbox) {
        ListDirectoryTool tool = new ListDirectoryTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.fs_list_directory(str(args, "path")),
                "Filesystem list_directory completed");
    }

    /** 目录树 */
    public static ToolCallback directoryTree(Sandbox sandbox) {
        DirectoryTreeTool tool = new DirectoryTreeTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.fs_directory_tree(str(args, "path")),
                "Filesystem directory_tree completed");
    }

    /** 搜索文件 */
    public static ToolCallback searchFiles(Sandbox sandbox) {
        SearchFilesTool tool = new SearchFilesTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.fs_search_files(str(args, "path"), str(args, "pattern"),
                        strArray(args, "excludePatterns")),
                "Filesystem search_files completed");
    }

    /** 文件信息 */
    public static ToolCallback getFileInfo(Sandbox sandbox) {
        GetFileInfoTool tool = new GetFileInfoTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.fs_get_file_info(str(args, "path")),
                "Filesystem get_file_info completed");
    }

    /* ---------------- 写入类（base 容器） ---------------- */

    /** 写文件 */
    public static ToolCallback writeFile(Sandbox sandbox) {
        WriteFileTool tool = new WriteFileTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.fs_write_file(str(args, "path"), str(args, "content")),
                "Filesystem write_file completed");
    }

    /** 编辑文件（oldText/newText 替换数组） */
    @SuppressWarnings("unchecked")
    public static ToolCallback editFile(Sandbox sandbox) {
        EditFileTool tool = new EditFileTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> {
            Object edits = args.get("edits");
            Map<String, Object>[] array = edits instanceof List<?> list
                    ? list.toArray(new Map[0]) : new Map[0];
            return tool.fs_edit_file(str(args, "path"), array);
        }, "Filesystem edit_file completed");
    }

    /** 建目录 */
    public static ToolCallback createDirectory(Sandbox sandbox) {
        CreateDirectoryTool tool = new CreateDirectoryTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.fs_create_directory(str(args, "path")),
                "Filesystem create_directory completed");
    }

    /** 移动/重命名 */
    public static ToolCallback moveFile(Sandbox sandbox) {
        MoveFileTool tool = new MoveFileTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.fs_move_file(str(args, "source"), str(args, "destination")),
                "Filesystem move_file completed");
    }

    /* ---------------- 浏览器类（browser 容器） ---------------- */

    /** 页面导航 */
    public static ToolCallback browserNavigate(Sandbox sandbox) {
        NavigateTool tool = new NavigateTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.browser_navigate(str(args, "url")),
                "Browser navigate completed");
    }

    /** 无障碍快照（抓 JS 渲染后正文） */
    public static ToolCallback browserSnapshot(Sandbox sandbox) {
        SnapshotTool tool = new SnapshotTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.browser_snapshot(),
                "Browser snapshot completed");
    }

    /** 点击元素 */
    public static ToolCallback browserClick(Sandbox sandbox) {
        ClickTool tool = new ClickTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.browser_click(str(args, "element"), str(args, "ref")),
                "Browser click completed");
    }

    /** 输入文本 */
    public static ToolCallback browserType(Sandbox sandbox) {
        TypeTool tool = new TypeTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.browser_type(str(args, "element"), str(args, "ref"),
                        str(args, "text"), bool(args, "submit"), bool(args, "slowly")),
                "Browser type completed");
    }

    /** 按键 */
    public static ToolCallback browserPressKey(Sandbox sandbox) {
        PressKeyTool tool = new PressKeyTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.browser_press_key(str(args, "key")),
                "Browser press key completed");
    }

    /** 关闭浏览器 */
    public static ToolCallback browserClose(Sandbox sandbox) {
        CloseTool tool = new CloseTool();
        tool.bind(sandbox);
        return callback(tool, (args, ctx) -> tool.browser_close(),
                "Browser close completed");
    }

    /* ---------------- 封装工具 ---------------- */

    /**
     * 通用封装：名称/描述/入参 schema 取 agentscope 工具定义（与旧 SAA 同源），
     * 入参按 {@code Map} 接收后由各工具分派器转成对应方法参数。
     */
    private static ToolCallback callback(SandboxTool tool,
                                         BiFunction<Map<String, Object>, ToolContext, String> executor,
                                         String message) {
        ObjectMapper mapper = new ObjectMapper();
        String inputSchema = "";
        try {
            inputSchema = mapper.writeValueAsString(tool.getSchema());
        } catch (Exception e) {
            log.error("生成工具入参 schema 失败: {}", e.getMessage());
        }
        // 用 LinkedHashMap（而非 Map.of）：工具失败信息/部分实现可能返回 null，Map.of 拒绝 null
        BiFunction<Map<String, Object>, ToolContext, Map<String, Object>> function = (args, ctx) -> {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("result", executor.apply(args, ctx));
            body.put("message", message);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("Response", body);
            return response;
        };
        return FunctionToolCallback
                .builder(tool.getName(), function)
                .description(tool.getDescription())
                .inputSchema(inputSchema)
                .inputType(Map.class)
                .toolMetadata(ToolMetadata.builder().returnDirect(false).build())
                .build();
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String[] strArray(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toArray(String[]::new);
        }
        return null;
    }

    private static Boolean bool(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Boolean b ? b : null;
    }
}
