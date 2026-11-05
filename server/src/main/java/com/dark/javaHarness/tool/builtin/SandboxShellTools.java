package com.dark.javaHarness.tool.builtin;

import io.agentscope.runtime.sandbox.box.Sandbox;
import io.agentscope.runtime.sandbox.tools.base.RunPythonTool;
import io.agentscope.runtime.sandbox.tools.base.RunShellCommandTool;
import org.springframework.ai.tool.ToolCallback;

/**
 * 沙箱执行类工具（base 容器）：Python 代码与 Shell 命令。
 */
public final class SandboxShellTools {

    private SandboxShellTools() {
    }

    /** Python 代码执行（run_ipython_cell） */
    public static ToolCallback runPythonCode(Sandbox sandbox) {
        RunPythonTool tool = new RunPythonTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool, (args, ctx) -> tool.run_ipython_cell(SandboxToolSupport.str(args, "code")),
                "Code execution completed");
    }

    /** Shell 命令执行（run_shell_command） */
    public static ToolCallback runShellCommand(Sandbox sandbox) {
        RunShellCommandTool tool = new RunShellCommandTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.run_shell_command(SandboxToolSupport.str(args, "command")),
                "Shell execution completed");
    }
}
