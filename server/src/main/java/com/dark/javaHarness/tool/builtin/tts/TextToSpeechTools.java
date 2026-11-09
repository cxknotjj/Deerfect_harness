package com.dark.javaHarness.tool.builtin.tts;

import com.dark.javaHarness.observability.HarnessMetrics;
import com.dark.javaHarness.tool.trace.ToolCallTracer;
import com.dark.javaHarness.voice.TtsProperties;
import com.dark.javaHarness.contract.api.VoiceArtifactStore;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 文字转语音工具：纯「文本 → mp3」能力，不感知调用渠道、不负责发送。
 *
 * <p>音频按本轮 turnId 存入 {@link VoiceArtifactStore}；core 在 ChatResponse.flags 中
 * 追加 voice:&lt;id&gt;，QQ 渠道按 id 取音频发送，其他渠道忽略 flag 照常展示文字。
 */
@Component
public class TextToSpeechTools {

    private final TtsProperties props;
    private final EdgeTtsClient edgeTts;
    private final VoiceArtifactStore artifacts;

    public TextToSpeechTools(TtsProperties props, EdgeTtsClient edgeTts, VoiceArtifactStore artifacts) {
        this.props = props;
        this.edgeTts = edgeTts;
        this.artifacts = artifacts;
    }

    /**
     * 把一段文字合成为语音，供支持语音的渠道发送。
     *
     * @param text    要朗读的文字（1~200 字）
     * @param context 工具上下文（turnId 用于关联本轮语音）
     */
    @Tool(name = "text_to_speech", description = "把一段文字合成为语音，仅在用户明确要求语音时调用")
    public String textToSpeech(
            @ToolParam(description = "要朗读的文字，长度 1~200 字") String text,
            ToolContext context) {
        if (!props.isEnabled()) {
            return "语音功能未启用，请改用文字回答。";
        }
        String turnId = turnId(context);
        if (turnId == null) {
            return "无法关联本轮语音，请改用文字回答。";
        }
        String cleaned = clean(text);
        if (cleaned.isEmpty()) {
            return "朗读文本为空，请改用文字回答。";
        }
        if (cleaned.length() > props.getMaxChars()) {
            return "朗读文本超过 " + props.getMaxChars() + " 字，请缩短后重试。";
        }
        long startedAt = System.currentTimeMillis();
        try {
            byte[] audio = edgeTts.synthesize(cleaned);
            artifacts.put(turnId, cleaned, audio, "mp3");
            HarnessMetrics.ttsCall(true, System.currentTimeMillis() - startedAt, cleaned.length());
            return "语音已生成，本轮由支持语音的渠道发送；请不要再用文字重复这段内容。";
        } catch (EdgeTtsClient.TtsException e) {
            HarnessMetrics.ttsCall(false, System.currentTimeMillis() - startedAt, cleaned.length());
            return "语音生成失败：" + e.getMessage() + "，请改用文字回答。";
        }
    }

    private static String turnId(ToolContext context) {
        if (context == null) {
            return null;
        }
        Object value = context.getContext().get(ToolCallTracer.CTX_TURN_ID);
        return value == null ? null : String.valueOf(value);
    }

    /** 去控制字符并压缩空白；保留中文标点与自然停顿。 */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
