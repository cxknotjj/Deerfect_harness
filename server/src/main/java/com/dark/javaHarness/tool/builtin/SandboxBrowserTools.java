package com.dark.javaHarness.tool.builtin;

import io.agentscope.runtime.sandbox.box.Sandbox;
import io.agentscope.runtime.sandbox.tools.browser.ClickTool;
import io.agentscope.runtime.sandbox.tools.browser.CloseTool;
import io.agentscope.runtime.sandbox.tools.browser.NavigateTool;
import io.agentscope.runtime.sandbox.tools.browser.PressKeyTool;
import io.agentscope.runtime.sandbox.tools.browser.SnapshotTool;
import io.agentscope.runtime.sandbox.tools.browser.TypeTool;
import org.springframework.ai.tool.ToolCallback;

/**
 * 沙箱浏览器类工具（browser 容器）：导航、无障碍快照、点击、输入、按键、关闭。
 */
public final class SandboxBrowserTools {

    private SandboxBrowserTools() {
    }

    /** 页面导航 */
    public static ToolCallback browserNavigate(Sandbox sandbox) {
        NavigateTool tool = new NavigateTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.browser_navigate(SandboxToolSupport.str(args, "url")),
                "Browser navigate completed");
    }

    /** 无障碍快照（抓 JS 渲染后正文） */
    public static ToolCallback browserSnapshot(Sandbox sandbox) {
        SnapshotTool tool = new SnapshotTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool, (args, ctx) -> tool.browser_snapshot(),
                "Browser snapshot completed");
    }

    /** 点击元素 */
    public static ToolCallback browserClick(Sandbox sandbox) {
        ClickTool tool = new ClickTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.browser_click(SandboxToolSupport.str(args, "element"),
                        SandboxToolSupport.str(args, "ref")),
                "Browser click completed");
    }

    /** 输入文本 */
    public static ToolCallback browserType(Sandbox sandbox) {
        TypeTool tool = new TypeTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.browser_type(SandboxToolSupport.str(args, "element"),
                        SandboxToolSupport.str(args, "ref"),
                        SandboxToolSupport.str(args, "text"),
                        SandboxToolSupport.bool(args, "submit"),
                        SandboxToolSupport.bool(args, "slowly")),
                "Browser type completed");
    }

    /** 按键 */
    public static ToolCallback browserPressKey(Sandbox sandbox) {
        PressKeyTool tool = new PressKeyTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool,
                (args, ctx) -> tool.browser_press_key(SandboxToolSupport.str(args, "key")),
                "Browser press key completed");
    }

    /** 关闭浏览器 */
    public static ToolCallback browserClose(Sandbox sandbox) {
        CloseTool tool = new CloseTool();
        tool.bind(sandbox);
        return SandboxToolSupport.callback(tool, (args, ctx) -> tool.browser_close(),
                "Browser close completed");
    }
}
