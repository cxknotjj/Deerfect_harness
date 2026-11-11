package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 语音转码单测：命令行拼装、时长解析、以及「没有 ffmpeg」时的降级语义。
 *
 * <p>不跑真实 ffmpeg（测试机不一定有，CI 更没有）：转码失败的处理分支比转码本身更值得锁住。
 */
class FeishuAudioTranscoderTest {

    private static FeishuAudioTranscoder transcoder(FeishuProperties props) {
        return new FeishuAudioTranscoder(props);
    }

    @Test
    void command_convertsToOpusMono_withoutStdin() {
        FeishuProperties props = new FeishuProperties();

        List<String> command = transcoder(props).command(Path.of("in.mp3"), Path.of("out.opus"));

        assertEquals("ffmpeg", command.get(0));
        assertTrue(command.contains("-nostdin"), "必须禁掉 stdin：服务端没有终端，ffmpeg 会挂住");
        assertTrue(command.contains("libopus"), command.toString());
        assertEquals("32k", command.get(command.indexOf("-b:a") + 1));
        assertEquals("48000", command.get(command.indexOf("-ar") + 1));
        assertEquals("1", command.get(command.indexOf("-ac") + 1));
        assertEquals("out.opus", command.get(command.size() - 1));
    }

    @Test
    void parseDuration_readsFfmpegHeader() {
        String output = "Input #0, mp3, from 'in.mp3':\n"
                + "  Duration: 00:00:03.48, start: 0.000000, bitrate: 48 kb/s\n";

        assertEquals(3480, FeishuAudioTranscoder.parseDurationMs(output));
    }

    @Test
    void parseDuration_handlesHoursAndMinutes() {
        assertEquals(62100, FeishuAudioTranscoder.parseDurationMs("Duration: 00:01:02.10, start: 0"));
        assertEquals(3_600_000, FeishuAudioTranscoder.parseDurationMs("Duration: 01:00:00.00, start: 0"));
    }

    @Test
    void parseDuration_unknownOrMissing_returnsZero() {
        assertEquals(0, FeishuAudioTranscoder.parseDurationMs("Duration: N/A, bitrate: N/A"));
        assertEquals(0, FeishuAudioTranscoder.parseDurationMs(""));
        assertEquals(0, FeishuAudioTranscoder.parseDurationMs(null));
    }

    @Test
    void toOpus_missingFfmpeg_returnsEmptyInsteadOfThrowing() {
        FeishuProperties props = new FeishuProperties();
        props.getWs().getVoice().setFfmpeg("definitely-not-ffmpeg-xyz");

        Optional<FeishuAudioTranscoder.Encoded> encoded = transcoder(props).toOpus(new byte[] {1, 2, 3});

        assertTrue(encoded.isEmpty(), "ffmpeg 缺失时退回文字，不能抛异常打断回复");
    }

    @Test
    void toOpus_disabled_returnsEmpty() {
        FeishuProperties props = new FeishuProperties();
        props.getWs().getVoice().setEnabled(false);

        assertTrue(transcoder(props).toOpus(new byte[] {1}).isEmpty());
    }

    @Test
    void toOpus_emptyInput_returnsEmpty() {
        assertTrue(transcoder(new FeishuProperties()).toOpus(new byte[0]).isEmpty());
    }
}
