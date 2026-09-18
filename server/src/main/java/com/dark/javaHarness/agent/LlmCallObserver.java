package com.dark.javaHarness.agent;

import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import org.springframework.ai.chat.metadata.Usage;

/**
 * LLM 调用观测封装（llm_call_log 落库的调用侧口径，从 {@link AgentChatCaller} 提取）：
 * 成功/失败记录与 tokensEstimated 判定收敛于此，call/stream 两通道共用同一口径。
 * recorder 为 null 时直通（无观测场景/单测）。
 */
final class LlmCallObserver {

    /** 落库记录器（可 null：无观测场景下直通） */
    private final LlmCallRecorder recorder;

    LlmCallObserver(LlmCallRecorder recorder) {
        this.recorder = recorder;
    }

    /** 成功记录（流式）：streamUsage 末帧回传真实 usage 时记真实 token，无则按输出文本估算兜底 */
    void okStream(String sessionId, String agentName, String model, long start,
                  String content, Usage usage, PromptAssembler.PromptAttachments attachments) {
        Integer prompt = usage == null ? null : usage.getPromptTokens();
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        Integer total = usage == null ? null : usage.getTotalTokens();
        if (completion == null) {
            int tokens = LlmCallRecorder.estimateTokens(content);
            completion = tokens;
            total = tokens;
        }
        record(sessionId, agentName, model, true, true, prompt, completion, total, start, null, attachments);
    }

    /**
     * 失败记录：原因链展开后落 error_msg（供应商 4xx/5xx 的响应体在
     * HttpStatusCodeException 里，外层 wrapper 的 getMessage() 常为空或泛化）。
     */
    void error(String sessionId, String agentName, String model, boolean stream,
               long start, Exception e, PromptAssembler.PromptAttachments attachments) {
        record(sessionId, agentName, model, stream, false, null, null, null, start,
                LlmCallRecorder.describeError(e), attachments);
    }

    private void record(String sessionId, String agentName, String model, boolean stream, boolean ok,
                        Integer promptTokens, Integer completionTokens, Integer totalTokens,
                        long start, String errorMsg, PromptAssembler.PromptAttachments attachments) {
        if (recorder == null) {
            return;
        }
        recorder.record(new LlmCallLog(sessionId, agentName, model, stream, ok,
                promptTokens, completionTokens, totalTokens,
                /* tokensEstimated */ stream && completionTokens == null,
                System.currentTimeMillis() - start, errorMsg,
                attachments == null ? null : attachments.skills(),
                attachments == null ? null : attachments.tools(),
                attachments == null ? null : attachments.mcpTools()));
    }
}
