package com.dark.javaHarness.channel.feishu.ws;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * mp3 → opus 转码：飞书语音条只认 opus，core 的 TTS 只会出 mp3（QQ 渠道直接送 mp3 即可）。
 *
 * <p><b>为什么调外部 ffmpeg 而不是纯 Java</b>：JDK 既没有 mp3 解码器也没有 opus 编码器，
 * 为一件事引两个原生库不如直接调 ffmpeg；它已经是运维最熟悉的依赖，坏了自己就能排查。
 * 代价是运行环境必须有 ffmpeg——没有时返回空，调用方退回文字，不影响任何其它消息。
 *
 * <p><b>时长为什么从 ffmpeg 输出里取</b>：上传 opus 必须带 duration（毫秒），而为了这一个
 * 数字再起一次 ffprobe 就是多一次进程启动；ffmpeg 解析输入时本来就会打印
 * {@code Duration: 00:00:03.48}，同一份输出里拿就行。
 */
@Component
public class FeishuAudioTranscoder {

    private static final Logger log = LoggerFactory.getLogger(FeishuAudioTranscoder.class);

    /** 输入信息里的时长行：{@code Duration: 00:00:03.48, start: ...} */
    private static final Pattern DURATION =
            Pattern.compile("Duration:\\s*(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})");

    private final FeishuProperties props;

    /** ffmpeg 缺失只告警一次：每轮语音都刷一条同样的 warn 等于把日志淹掉 */
    private final AtomicBoolean missingLogged = new AtomicBoolean();

    public FeishuAudioTranscoder(FeishuProperties props) {
        this.props = props;
    }

    /** 转码结果：opus 字节 + 时长（毫秒，飞书上传必填） */
    public record Encoded(byte[] audio, int durationMs) {
    }

    /**
     * 转成 opus；开关关闭、ffmpeg 缺失、转码失败、读不到时长都返回空。
     *
     * <p>返回空不是异常：语音是锦上添花，发不出去应当退回文字，而不是让整条回复失败。
     */
    public Optional<Encoded> toOpus(byte[] audio) {
        return toOpus(audio, false);
    }

    /**
     * @param music true = 音乐（高码率 + 立体声），false = 语音
     */
    public Optional<Encoded> toOpus(byte[] audio, boolean music) {
        FeishuProperties.Ws.Voice cfg = props.getWs().getVoice();
        if (!cfg.isEnabled()) {
            return Optional.empty();
        }
        if (audio == null || audio.length == 0) {
            return Optional.empty();
        }
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("harness-voice-", ".mp3");
            out = Files.createTempFile("harness-voice-", ".opus");
            Files.write(in, audio);
            Process process = new ProcessBuilder(command(in, out, music))
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(cfg.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[feishu] 语音转码超时（{}s），本条退回文字", cfg.getTimeoutSeconds());
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                log.warn("[feishu] 语音转码失败 exit={}：{}", process.exitValue(), abbreviate(output));
                return Optional.empty();
            }
            int durationMs = parseDurationMs(output);
            if (durationMs <= 0) {
                log.warn("[feishu] 语音转码完成但读不到时长，无法上传（飞书要求必填）");
                return Optional.empty();
            }
            byte[] opus = Files.readAllBytes(out);
            if (opus.length == 0) {
                log.warn("[feishu] 语音转码输出为空");
                return Optional.empty();
            }
            return Optional.of(new Encoded(opus, durationMs));
        } catch (IOException e) {
            if (missingLogged.compareAndSet(false, true)) {
                log.warn("[feishu] 调不起 ffmpeg（{}），语音回复退回文字；"
                        + "要发语音条请用 channel.feishu.ws.voice.ffmpeg 指定可执行文件路径",
                        cfg.getFfmpeg());
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    /**
     * 命令行（包内可见：单测直接校验参数）。
     *
     * <p>{@code -nostdin} 不能省：否则 ffmpeg 可能去读标准输入，在「没接终端」的进程里
     * 直接挂住——服务端正是这种环境。
     */
    List<String> command(Path in, Path out, boolean music) {
        FeishuProperties.Ws.Voice cfg = props.getWs().getVoice();
        String bitrate = music ? cfg.getMusic().getBitrate() : cfg.getBitrate();
        int channels = music ? cfg.getMusic().getChannels() : cfg.getChannels();
        return List.of(
                cfg.getFfmpeg(),
                "-hide_banner",
                "-nostdin",
                "-y",
                "-i", in.toString(),
                "-c:a", "libopus",
                "-b:a", bitrate,
                "-ar", "48000",
                "-ac", String.valueOf(channels),
                out.toString());
    }

    /** 从 ffmpeg 输出解析时长（毫秒）；{@code Duration: N/A} 或格式不符返回 0 */
    static int parseDurationMs(String output) {
        if (output == null || output.isEmpty()) {
            return 0;
        }
        Matcher matcher = DURATION.matcher(output);
        if (!matcher.find()) {
            return 0;
        }
        long hours = Long.parseLong(matcher.group(1));
        long minutes = Long.parseLong(matcher.group(2));
        long seconds = Long.parseLong(matcher.group(3));
        long centis = Long.parseLong(matcher.group(4));
        long total = ((hours * 3600 + minutes * 60 + seconds) * 1000) + centis * 10;
        return total > Integer.MAX_VALUE ? 0 : (int) total;
    }

    private static String abbreviate(String output) {
        String trimmed = output == null ? "" : output.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
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
}
