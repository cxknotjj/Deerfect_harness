package com.dark.javaHarness.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 沙箱边界守卫单测（P0-2）：写入路径归一化与越界拒绝、破坏性命令拦截、结果截断。
 *
 * <p>这些都是纯函数，覆盖的是「模型给出各种写法时服务端如何判定」，与容器无关。
 */
class SandboxGuardsTest {

    // ---------- 写入路径 ----------

    @Test
    void relativePath_resolvedUnderWorkspace() {
        assertEquals("/workspace/notes/a.md", SandboxGuards.requireWorkspacePath("notes/a.md"));
        assertEquals("/workspace/report.txt", SandboxGuards.requireWorkspacePath("./report.txt"));
    }

    @Test
    void absolutePathInsideWorkspace_allowed() {
        assertEquals("/workspace/data/out.csv",
                SandboxGuards.requireWorkspacePath("/workspace/data/out.csv"));
        assertEquals("/workspace", SandboxGuards.requireWorkspacePath("/workspace"));
    }

    @Test
    void duplicateSlashesAndDotSegments_normalized() {
        assertEquals("/workspace/a/b/c", SandboxGuards.requireWorkspacePath("a//./b/./c"));
        assertEquals("/workspace/a/c", SandboxGuards.requireWorkspacePath("/workspace/a/./b/../c"));
    }

    /** 相对路径里的 .. 不能逃出工作区：这是模型最常写的越界形式 */
    @Test
    void relativeTraversal_rejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SandboxGuards.requireWorkspacePath("../../etc/passwd"));
        assertTrue(e.getMessage().contains("/workspace"), "错误信息应说明要求的工作区: " + e.getMessage());
    }

    @Test
    void absoluteTraversal_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SandboxGuards.requireWorkspacePath("/workspace/../etc/shadow"));
        assertThrows(IllegalArgumentException.class,
                () -> SandboxGuards.requireWorkspacePath("/etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> SandboxGuards.requireWorkspacePath("/"));
    }

    /** ~ 不展开：容器里的家目录取决于镜像用户，静默替换会让模型以为写到了别处 */
    @Test
    void tildePath_rejectedWithHint() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SandboxGuards.requireWorkspacePath("~/notes.md"));
        assertTrue(e.getMessage().contains("绝对路径"), "应提示改用绝对路径: " + e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SandboxGuards.requireWorkspacePath("~"));
    }

    @Test
    void blankPath_rejected() {
        assertThrows(IllegalArgumentException.class, () -> SandboxGuards.requireWorkspacePath(null));
        assertThrows(IllegalArgumentException.class, () -> SandboxGuards.requireWorkspacePath("   "));
    }

    // ---------- Shell 命令 ----------

    @Test
    void destructiveCommands_rejected() {
        for (String command : new String[]{
                "rm -rf /",
                "rm -rf /*",
                "rm -rf ~",
                "sudo rm -rf /",
                "mkfs.ext4 /dev/sda1",
                "dd if=/dev/zero of=/dev/sda bs=1M",
                "shutdown -h now",
                "reboot",
                ":(){ :|:& };:",
                "chmod -R 777 /"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SandboxGuards.requireSafeCommand(command),
                    "应拒绝破坏性命令: " + command);
        }
    }

    /** 工作区内的常规操作不能被误伤 */
    @Test
    void normalCommands_allowed() {
        for (String command : new String[]{
                "ls -la /workspace",
                "rm -rf /workspace/tmp/build",
                "python main.py --input data.csv",
                "mkdir -p /workspace/out && cat a.txt > b.txt",
                "chmod 777 /workspace/script.sh",
                "grep -rn TODO /workspace"}) {
            SandboxGuards.requireSafeCommand(command);
        }
    }

    @Test
    void blankCommand_rejected() {
        assertThrows(IllegalArgumentException.class, () -> SandboxGuards.requireSafeCommand(null));
        assertThrows(IllegalArgumentException.class, () -> SandboxGuards.requireSafeCommand(" "));
    }

    // ---------- 结果截断 ----------

    @Test
    void shortResult_untouched() {
        String result = "ok";
        assertEquals(result, SandboxGuards.truncateResult(result));
        assertEquals("", SandboxGuards.truncateResult(""));
        assertEquals(null, SandboxGuards.truncateResult(null));
    }

    /** 截断必须注明原始长度：否则模型会把「被裁剪」误判成「输出就这么多」 */
    @Test
    void longResult_truncatedWithOriginalLength() {
        String result = "x".repeat(SandboxGuards.MAX_RESULT_CHARS + 500);

        String truncated = SandboxGuards.truncateResult(result);

        assertTrue(truncated.length() > SandboxGuards.MAX_RESULT_CHARS);
        assertTrue(truncated.startsWith("x".repeat(100)));
        assertTrue(truncated.contains("原始长度 " + result.length()), "应注明原始长度: " + truncated.substring(
                Math.max(0, truncated.length() - 60)));
        assertFalse(truncated.length() > SandboxGuards.MAX_RESULT_CHARS + 100);
    }
}
