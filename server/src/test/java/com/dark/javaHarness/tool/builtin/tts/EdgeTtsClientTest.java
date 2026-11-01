package com.dark.javaHarness.tool.builtin.tts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.voice.TtsProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * EdgeTtsClient：命令参数组装、子进程成功/失败/空输出、配置校验。
 * 子进程用临时 .cmd 替身模拟 edge-tts，避免单测依赖本机 Python 与网络。
 */
class EdgeTtsClientTest {

    private static TtsProperties props(Path python) {
        TtsProperties props = new TtsProperties();
        props.setEnabled(true);
        props.setPython(python.toString());
        props.setVoice("zh-CN-XiaoxiaoNeural");
        props.setRate("+0%");
        props.setVolume("+0%");
        props.setMaxChars(200);
        props.setTimeoutSeconds(5);
        props.setMaxConcurrent(2);
        return props;
    }

    @Test
    void command_buildsEdgeTtsArgumentsInOrder(@TempDir Path dir) {
        TtsProperties props = props(Path.of("python3.11"));
        List<String> command = new EdgeTtsClient(props)
                .command(dir.resolve("text.txt"), dir.resolve("audio.mp3"));

        assertEquals(List.of("python3.11", "-m", "edge_tts",
                "--voice", "zh-CN-XiaoxiaoNeural",
                "--rate=+0%", "--volume=+0%",
                "--file", dir.resolve("text.txt").toString(),
                "--write-media", dir.resolve("audio.mp3").toString()), command);
    }

    @Test
    void synthesize_disabled_throws() {
        TtsProperties props = props(Path.of("python3.11"));
        props.setEnabled(false);
        assertThrows(EdgeTtsClient.TtsException.class,
                () -> new EdgeTtsClient(props).synthesize("你好"));
    }

    @Test
    void synthesize_blankText_throws() {
        TtsProperties props = props(Path.of("python3.11"));
        assertThrows(EdgeTtsClient.TtsException.class,
                () -> new EdgeTtsClient(props).synthesize("  "));
    }

    @Test
    void synthesize_overMaxChars_throws() {
        TtsProperties props = props(Path.of("python3.11"));
        props.setMaxChars(3);
        assertThrows(EdgeTtsClient.TtsException.class,
                () -> new EdgeTtsClient(props).synthesize("一二三四"));
    }

    @Test
    void synthesize_missingPython_throwsWithConfigHint(@TempDir Path dir) {
        TtsProperties props = props(dir.resolve("no-such-python.exe"));
        EdgeTtsClient.TtsException e = assertThrows(EdgeTtsClient.TtsException.class,
                () -> new EdgeTtsClient(props).synthesize("你好"));
        assertTrue(e.getMessage().contains("app.tts.python"), e.getMessage());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void synthesize_success_returnsAudio(@TempDir Path dir) throws IOException {
        Path fake = fakePython(dir, "ok");
        byte[] audio = new EdgeTtsClient(props(fake)).synthesize("你好");

        assertArrayEquals("fake-audio\r\n".getBytes(StandardCharsets.UTF_8), audio);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void synthesize_emptyOutput_throws(@TempDir Path dir) throws IOException {
        Path fake = fakePython(dir, "empty");
        EdgeTtsClient.TtsException e = assertThrows(EdgeTtsClient.TtsException.class,
                () -> new EdgeTtsClient(props(fake)).synthesize("你好"));
        assertTrue(e.getMessage().contains("输出为空"), e.getMessage());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void synthesize_nonZeroExit_throwsWithExitCode(@TempDir Path dir) throws IOException {
        Path fake = fakePython(dir, "fail");
        EdgeTtsClient.TtsException e = assertThrows(EdgeTtsClient.TtsException.class,
                () -> new EdgeTtsClient(props(fake)).synthesize("你好"));
        assertTrue(e.getMessage().contains("exit=3"), e.getMessage());
    }

    /** 临时 .cmd 替身：忽略 edge-tts 参数，只按模式写输出文件/返回非 0 退出码。 */
    private static Path fakePython(Path dir, String mode) throws IOException {
        String tail = switch (mode) {
            case "ok" -> "echo fake-audio>\"%LAST%\"\r\n";
            case "empty" -> "type nul>\"%LAST%\"\r\n";
            case "fail" -> "exit /b 3\r\n";
            default -> throw new IllegalArgumentException(mode);
        };
        String script = "@echo off\r\n"
                + "set LAST=\r\n"
                + ":loop\r\n"
                + "if \"%~1\"==\"\" goto end\r\n"
                + "set LAST=%~1\r\n"
                + "shift\r\n"
                + "goto loop\r\n"
                + ":end\r\n"
                + tail;
        Path file = dir.resolve("fake-python-" + mode + ".cmd");
        Files.writeString(file, script, StandardCharsets.UTF_8);
        return file;
    }
}
