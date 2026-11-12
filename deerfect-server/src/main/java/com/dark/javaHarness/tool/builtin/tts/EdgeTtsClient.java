package com.dark.javaHarness.tool.builtin.tts;

import com.dark.javaHarness.voice.TtsProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Edge TTS 子进程客户端：把文本写入临时 UTF-8 文件，调 python -m edge_tts 生成 mp3。
 *
 * <p>不经过 shell，文本不作为命令行参数（规避中文编码与 Windows 长度限制）。
 * 超时强制杀进程；并发信号量限制同时运行的子进程数。失败统一抛 {@link TtsException}，
 * 由工具转成给模型的错误文案。
 */
@Component
public class EdgeTtsClient {

    private static final Logger log = LoggerFactory.getLogger(EdgeTtsClient.class);

    private final TtsProperties props;
    private final Semaphore permits;

    public EdgeTtsClient(TtsProperties props) {
        this.props = props;
        this.permits = new Semaphore(Math.max(1, props.getMaxConcurrent()), true);
    }

    /** 合成语音，返回 mp3 字节；调用方负责长度校验。 */
    public byte[] synthesize(String text) {
        if (!props.isEnabled()) {
            throw new TtsException("语音功能未启用");
        }
        if (text == null || text.isBlank()) {
            throw new TtsException("朗读文本为空");
        }
        if (text.length() > props.getMaxChars()) {
            throw new TtsException("朗读文本超过 " + props.getMaxChars() + " 字，请缩短");
        }
        if (!permits.tryAcquire()) {
            throw new TtsException("语音合成繁忙，请稍后再试");
        }
        Path textFile = null;
        Path audioFile = null;
        long startedAt = System.currentTimeMillis();
        try {
            textFile = Files.createTempFile("harness-tts-", ".txt");
            audioFile = Files.createTempFile("harness-tts-", ".mp3");
            Files.writeString(textFile, text, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(command(textFile, audioFile))
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TtsException("语音合成超时（" + props.getTimeoutSeconds() + "s）");
            }
            int exit = process.exitValue();
            String output = readOutput(process);
            if (exit != 0) {
                throw new TtsException("Edge TTS 失败（exit=" + exit + "）：" + output);
            }
            byte[] audio = Files.readAllBytes(audioFile);
            if (audio.length == 0) {
                throw new TtsException("Edge TTS 输出为空");
            }
            log.info("[tts] 合成完成 chars={} bytes={} cost={}ms",
                    text.length(), audio.length, System.currentTimeMillis() - startedAt);
            return audio;
        } catch (IOException e) {
            throw new TtsException("Edge TTS 启动/读取失败（检查 app.tts.python）: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsException("语音合成被中断");
        } finally {
            permits.release();
            deleteQuietly(textFile);
            deleteQuietly(audioFile);
        }
    }

    /** Edge TTS 命令参数数组（包内可见：单测直接校验参数顺序与拼接）。 */
    List<String> command(Path textFile, Path audioFile) {
        return List.of(
                props.getPython(),
                "-m", "edge_tts",
                "--voice", props.getVoice(),
                "--rate=" + props.getRate(),
                "--volume=" + props.getVolume(),
                "--file", textFile.toString(),
                "--write-media", audioFile.toString());
    }

    /** 读取子进程输出（进程已结束）：只保留前 2000 字用于排错。 */
    private static String readOutput(Process process) throws IOException {
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return output.length() <= 2000 ? output : output.substring(0, 2000);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主流程
        }
    }

    /** TTS 合成失败：工具捕获后转为给模型看的错误文案。 */
    public static class TtsException extends RuntimeException {
        public TtsException(String message) {
            super(message);
        }
    }
}
