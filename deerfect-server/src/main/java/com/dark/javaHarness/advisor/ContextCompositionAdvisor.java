package com.dark.javaHarness.advisor;

import com.dark.javaHarness.prompt.ContextCompositionRecorder;
import com.dark.javaHarness.prompt.SystemAssembly;
import java.util.List;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * 上下文构成快照拦截器（Path A）：
 * 在 {@link ContextAssemblingAdvisor}（+1）与 {@link PromptBudgetAdvisor}（+2）裁剪之后、
 * 真实请求发出之前读取最终 messages，交给 {@link ContextCompositionRecorder} 按会话记录。
 * 只读请求、不改写，记录失败由 recorder 内部兜底，不影响调用链。
 */
public final class ContextCompositionAdvisor implements CallAdvisor, StreamAdvisor {

    private final String agentName;
    private final String sessionId;
    private final String model;
    private final String turnId;
    private final SystemAssembly system;
    private final int toolSchemaTokens;
    private final ContextCompositionRecorder recorder;

    public ContextCompositionAdvisor(String agentName, String sessionId, String model, String turnId,
                                     SystemAssembly system, int toolSchemaTokens,
                                     ContextCompositionRecorder recorder) {
        this.agentName = agentName;
        this.sessionId = sessionId;
        this.model = model;
        this.turnId = turnId;
        this.system = system;
        this.toolSchemaTokens = toolSchemaTokens;
        this.recorder = recorder;
    }

    @Override
    public String getName() {
        return "context-composition-advisor";
    }

    @Override
    public int getOrder() {
        // 晚于 ContextAssembling(+1)、PromptBudget(+2)、LlmRequestLog(+3)：看到预算裁剪后的最终请求
        return Ordered.HIGHEST_PRECEDENCE + 4;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        record(request);
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        record(request);
        return chain.nextStream(request);
    }

    private void record(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        recorder.record(sessionId, agentName, model, turnId, system, toolSchemaTokens, messages);
    }
}
