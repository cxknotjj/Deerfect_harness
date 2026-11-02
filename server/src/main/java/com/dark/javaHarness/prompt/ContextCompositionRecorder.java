package com.dark.javaHarness.prompt;

import com.dark.javaHarness.domain.dto.ContextComposition;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

/**
 * 上下文快照写入器（Path A / Path B 共用的记录入口）：
 * 委托 {@link ContextCompositionCalculator} 计算后写入 {@link ContextCompositionStore}。
 * 观测为纯增强：sessionId 为空直接跳过；计算/存储异常只告警，绝不影响 LLM 主链路。
 */
@Component
public class ContextCompositionRecorder {

    private static final Logger log = LoggerFactory.getLogger(ContextCompositionRecorder.class);

    private final ContextCompositionStore store;

    public ContextCompositionRecorder(ContextCompositionStore store) {
        this.store = store;
    }

    public void record(String sessionId, String agentName, String model, String turnId,
                       SystemAssembly system, int toolSchemaTokens, List<Message> finalMessages) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            ContextComposition composition = ContextCompositionCalculator.build(
                    sessionId, agentName, model, turnId, system, toolSchemaTokens, finalMessages);
            store.put(composition);
        } catch (RuntimeException e) {
            log.warn("[context-composition] 快照记录失败（不影响主链路）：{}", e.toString());
        }
    }
}
