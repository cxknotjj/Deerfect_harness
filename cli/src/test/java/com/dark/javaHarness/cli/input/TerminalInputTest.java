package com.dark.javaHarness.cli.input;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * TerminalInput 单测：降级输入路径（标准行式读取——EOF 返 null、提示符写入输出通道、
 * out() 返回配套通道）与 open() 烟雾（任意环境可打开、可安全关闭，不断言具体实现——
 * 测试环境有无 TTY 因机器而异，read() 在 JLine 实现上会阻塞故不可调用）。
 */
class TerminalInputTest {

    @Test
    void legacy_readsLine_thenEof() {
        ByteArrayInputStream in = new ByteArrayInputStream("你好\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);

        TerminalInput.LineInput input = TerminalInput.legacy(in, out);

        assertEquals("你好", input.read(), "降级输入按行读取");
        assertNull(input.read(), "EOF 返回 null（chatLoop 退出条件）");
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("你> "), "提示符写入输出通道");
    }

    @Test
    void legacy_out_returnsConfiguredChannel() {
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        TerminalInput.LineInput input = TerminalInput.legacy(new ByteArrayInputStream(new byte[0]), out);

        assertSame(out, input.out(), "out() 返回构造时传入的输出通道");
    }

    @Test
    void open_returnsUsableInput_andShutdownSafe() {
        TerminalInput.LineInput input = TerminalInput.open();

        assertNotNull(input, "任意环境（TTY/无 TTY）都应返回可用输入源");
        assertNotNull(input.out());
        input.shutdown(); // 不抛出（JLine flush / 降级实现空操作）
    }

    @Test
    void jline_cbreak_disablesEchoAndCanonical_preservesOutputPostProcessing() throws Exception {
        org.jline.terminal.Terminal terminal = org.mockito.Mockito.mock(org.jline.terminal.Terminal.class);
        org.jline.terminal.Attributes cooked = new org.jline.terminal.Attributes();
        cooked.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ICANON, true);
        cooked.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ECHO, true);
        cooked.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ISIG, true);
        cooked.setOutputFlag(org.jline.terminal.Attributes.OutputFlag.OPOST, true);
        cooked.setOutputFlag(org.jline.terminal.Attributes.OutputFlag.ONLCR, true);
        cooked.setInputFlag(org.jline.terminal.Attributes.InputFlag.ICRNL, true);
        cooked.setInputFlag(org.jline.terminal.Attributes.InputFlag.IXON, true);
        org.mockito.Mockito.when(terminal.getAttributes()).thenReturn(cooked);

        TerminalInput.enterCbreak(terminal);

        org.mockito.ArgumentCaptor<org.jline.terminal.Attributes> captor =
                org.mockito.ArgumentCaptor.forClass(org.jline.terminal.Attributes.class);
        org.mockito.Mockito.verify(terminal).setAttributes(captor.capture());
        org.jline.terminal.Attributes a = captor.getValue();
        assertFalse(a.getLocalFlag(org.jline.terminal.Attributes.LocalFlag.ICANON), "关行规程：抢跑按键不被行规程缓冲/回显");
        assertFalse(a.getLocalFlag(org.jline.terminal.Attributes.LocalFlag.ECHO), "关回显：输出期间抢跑按键不上屏");
        assertFalse(a.getInputFlag(org.jline.terminal.Attributes.InputFlag.ICRNL), "\r 原样进 JLine（readLine 标准 accept 路径）");
        assertFalse(a.getInputFlag(org.jline.terminal.Attributes.InputFlag.IXON), "Ctrl+S/Q 不冻结输出");
        assertTrue(a.getOutputFlag(org.jline.terminal.Attributes.OutputFlag.OPOST), "保留 OPOST：程序输出 \n 照常转 \r\n 不阶梯");
        assertTrue(a.getOutputFlag(org.jline.terminal.Attributes.OutputFlag.ONLCR), "保留 ONLCR");
        assertTrue(a.getLocalFlag(org.jline.terminal.Attributes.LocalFlag.ISIG), "保留 ISIG：Ctrl+C 语义与现状一致");
    }

    @Test
    void restoreQuietly_restoresOriginal_andToleratesRepeatAfterClose() throws Exception {
        org.jline.terminal.Terminal terminal = org.mockito.Mockito.mock(org.jline.terminal.Terminal.class);
        org.jline.terminal.Attributes original = new org.jline.terminal.Attributes();

        TerminalInput.restoreQuietly(terminal, original);
        org.mockito.Mockito.verify(terminal).setAttributes(original);

        // 终端已关闭后的重复恢复（shutdown + JVM hook 双路径）：吞异常不抛出
        org.mockito.Mockito.doThrow(new IllegalStateException("closed")).when(terminal).setAttributes(original);
        assertDoesNotThrow(() -> TerminalInput.restoreQuietly(terminal, original));
    }
}
