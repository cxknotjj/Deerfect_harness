package com.dark.javaHarness.tool.builtin;

import io.agentscope.runtime.sandbox.box.Sandbox;
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
import org.springframework.ai.tool.ToolCallback;

/**
 * 沙箱文件类工具（base 容器）：只读（读/批量读/列目录/目录树/搜索/信息）与写入（写/编辑/建目录/移动）。
 */
public final class SandboxFileTools {

    private SandboxFileTools() {
    }

    /* ---------------- 只读 ---------------- */

    /** 读单个文件 */
    public static ToolCallback readFile(Sandbox sandbox) {
        ReadFileTool tool = new ReadFileTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.fs_read_file(SandboxToolSupport.str(args, "path")),
                "Filesystem read_file completed");
    }

    /** 批量读文件 */
    public static ToolCallback readMultipleFiles(Sandbox sandbox) {
        ReadMultipleFilesTool tool = new ReadMultipleFilesTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.fs_read_multiple_files(SandboxToolSupport.strArray(args, "paths")),
                "Filesystem read_multiple_files completed");
    }

    /** 列目录 */
    public static ToolCallback listDirectory(Sandbox sandbox) {
        ListDirectoryTool tool = new ListDirectoryTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.fs_list_directory(SandboxToolSupport.str(args, "path")),
                "Filesystem list_directory completed");
    }

    /** 目录树 */
    public static ToolCallback directoryTree(Sandbox sandbox) {
        DirectoryTreeTool tool = new DirectoryTreeTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.fs_directory_tree(SandboxToolSupport.str(args, "path")),
                "Filesystem directory_tree completed");
    }

    /** 搜索文件 */
    public static ToolCallback searchFiles(Sandbox sandbox) {
        SearchFilesTool tool = new SearchFilesTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.fs_search_files(SandboxToolSupport.str(args, "path"),
                        SandboxToolSupport.str(args, "pattern"),
                        SandboxToolSupport.strArray(args, "excludePatterns")),
                "Filesystem search_files completed");
    }

    /** 文件信息 */
    public static ToolCallback getFileInfo(Sandbox sandbox) {
        GetFileInfoTool tool = new GetFileInfoTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.fs_get_file_info(SandboxToolSupport.str(args, "path")),
                "Filesystem get_file_info completed");
    }

    /* ---------------- 写入 ---------------- */

    /** 写文件（路径经 SandboxGuards 归一化并限制在工作区内） */
    public static ToolCallback writeFile(Sandbox sandbox) {
        WriteFileTool tool = new WriteFileTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.fs_write_file(
                        SandboxGuards.requireWorkspacePath(SandboxToolSupport.str(args, "path")),
                        SandboxToolSupport.str(args, "content")),
                "Filesystem write_file completed");
    }

    /** 编辑文件（oldText/newText 替换数组） */
    @SuppressWarnings("unchecked")
    public static ToolCallback editFile(Sandbox sandbox) {
        EditFileTool tool = new EditFileTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool, (args, ctx) -> {
            Object edits = args.get("edits");
            Map<String, Object>[] array = edits instanceof List<?> list
                    ? list.toArray(new Map[0]) : new Map[0];
            return tool.fs_edit_file(
                    SandboxGuards.requireWorkspacePath(SandboxToolSupport.str(args, "path")), array);
        }, "Filesystem edit_file completed");
    }

    /** 建目录 */
    public static ToolCallback createDirectory(Sandbox sandbox) {
        CreateDirectoryTool tool = new CreateDirectoryTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.fs_create_directory(
                        SandboxGuards.requireWorkspacePath(SandboxToolSupport.str(args, "path"))),
                "Filesystem create_directory completed");
    }

    /** 移动/重命名（源与目标都必须在工作区内，否则等于把文件搬出沙箱工作区） */
    public static ToolCallback moveFile(Sandbox sandbox) {
        MoveFileTool tool = new MoveFileTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.fs_move_file(
                        SandboxGuards.requireWorkspacePath(SandboxToolSupport.str(args, "source")),
                        SandboxGuards.requireWorkspacePath(SandboxToolSupport.str(args, "destination"))),
                "Filesystem move_file completed");
    }
}
