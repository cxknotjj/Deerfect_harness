package com.dark.javaHarness.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.tool.trace.ToolCallTracer;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

/** TextToSpeechTools：纯文本→音频，校验输入、写入工件库、错误转成模型可读文案。 */
@ExtendWith(MockitoExtension.class)
class TextToSpeechToolsTest {

    @Mock
    private EdgeTtsClient edgeTts;
    @Mock
    private VoiceArtifactStore artifacts;

    private TtsProperties props;
    private TextToSpeechTools tools;

    @BeforeEach
    void setUp() {
        props = new TtsProperties();
        props.setEnabled(true);
        props.setMaxChars(200);
        tools = new TextToSpeechTools(props, edgeTts, artifacts);
    }

    private static ToolContext context(String turnId) {
        return new ToolContext(Map.of(ToolCallTracer.CTX_TURN_ID, turnId));
    }

    @Test
    void disabled_returnsFallbackHint() {
        props.setEnabled(false);
        assertEquals("语音功能未启用，请改用文字回答。", tools.textToSpeech("你好", context("turn-1")));
    }

    @Test
    void missingTurnId_returnsFallbackHint() {
        assertEquals("无法关联本轮语音，请改用文字回答。", tools.textToSpeech("你好", null));
        assertEquals("无法关联本轮语音，请改用文字回答。",
                tools.textToSpeech("你好", new ToolContext(Map.of())));
    }

    @Test
    void blankText_returnsFallbackHint() {
        assertTrue(tools.textToSpeech(" \n\t ", context("turn-1")).contains("为空"));
    }

    @Test
    void overMaxChars_returnsShrinkHint() {
        props.setMaxChars(3);
        String result = tools.textToSpeech("一二三四", context("turn-1"));
        assertTrue(result.contains("超过 3 字"), result);
    }

    @Test
    void success_synthesizesAndStoresArtifact() {
        when(edgeTts.synthesize("你好")).thenReturn(new byte[]{1, 2});
        when(artifacts.put(eq("turn-1"), eq("你好"), any(byte[].class), eq("mp3"))).thenReturn("voice-1");

        String result = tools.textToSpeech("你好", context("turn-1"));

        assertTrue(result.contains("语音已生成"), result);
        verify(artifacts).put(eq("turn-1"), eq("你好"), any(byte[].class), eq("mp3"));
    }

    @Test
    void synthesisFailure_returnsErrorHint() {
        when(edgeTts.synthesize("你好")).thenThrow(new EdgeTtsClient.TtsException("网络不可用"));

        String result = tools.textToSpeech("你好", context("turn-1"));

        assertTrue(result.contains("语音生成失败"), result);
        assertTrue(result.contains("网络不可用"), result);
    }

    @Test
    void textIsCleanedBeforeSynthesis() {
        when(edgeTts.synthesize("你好 世界")).thenReturn(new byte[]{1});

        tools.textToSpeech("  你好\n\n世界  ", context("turn-1"));

        verify(edgeTts).synthesize("你好 世界");
    }
}
