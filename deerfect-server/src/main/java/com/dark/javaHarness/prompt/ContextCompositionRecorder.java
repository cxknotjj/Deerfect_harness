package com.dark.javaHarness.prompt;

import com.dark.javaHarness.domain.dto.ContextComposition;
import com.dark.javaHarness.domain.entity.ContextCompositionLogEntity;
import com.dark.javaHarness.mapper.ContextCompositionLogMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 上下文快照写入器（Path A / Path B 共用的记录入口）：
 * 委托 {@link ContextCompositionCalculator} 计算后写入内存 {@link ContextCompositionStore}
 * （热读，30 分钟 TTL），并异步落库 context_composition_log（冷存，供重启后/历史查询）。
 *
 * <p>观测为纯增强：sessionId 为空直接跳过；计算/存储/落库异常只告警，绝不影响 LLM 主链路。
 * 落库沿用观测队列口径——单线程 + 有界队列，打满直接丢弃并周期 warn，不向调用方传播。
 */
@Component
public class ContextCompositionRecorder {

    private static final Logger log = LoggerFactory.getLogger(ContextCompositionRecorder.class);

    /** 落库队列容量：单行毫秒级写入，与 LlmCallRecorder 同口径，打满即丢弃保护主链路 */
    static final int QUEUE_CAPACITY = 512;
    private static final int DROP_LOG_INTERVAL = 100;

    private final ContextCompositionStore store;
    private final ContextCompositionLogMapper mapper;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();

    @Autowired
    public ContextCompositionRecorder(ContextCompositionStore store,
                                      ContextCompositionLogMapper mapper,
                                      ObjectMapper objectMapper) {
        this(store, mapper, objectMapper, QUEUE_CAPACITY);
    }

    /** 包内可见：单测用极小容量构造队列打满场景 */
    ContextCompositionRecorder(ContextCompositionStore store,
                               ContextCompositionLogMapper mapper,
                               ObjectMapper objectMapper,
                               int queueCapacity) {
        this.store = store;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "context-composition-recorder");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
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
            submit(composition);
        } catch (RuntimeException e) {
            log.warn("[context-composition] 快照记录失败（不影响主链路）：{}", e.toString());
        }
    }

    private void submit(ContextComposition composition) {
        try {
            executor.execute(() -> {
                try {
                    mapper.insert(toEntity(composition));
                } catch (Exception e) {
                    log.warn("[context-composition] 快照落库失败（不影响主链路）：{}", e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            long total = dropped.incrementAndGet();
            if (total % DROP_LOG_INTERVAL == 1) {
                log.warn("[context-composition] 落库队列已满，已丢弃 {} 条快照（不影响主链路）", total);
            }
        }
    }

    private ContextCompositionLogEntity toEntity(ContextComposition c) {
        ContextCompositionLogEntity e = new ContextCompositionLogEntity();
        e.setSessionId(c.sessionId());
        e.setAgentName(c.agentName());
        e.setModel(c.model());
        e.setTurnId(c.turnId());
        e.setEstimatedTotalTokens(c.estimatedTotalTokens());
        // 计算器保证 parts 可序列化；此处失败由调用层 warn 兜底
        e.setParts(objectMapper.writeValueAsString(c.parts()));
        e.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(c.capturedAt()), ZoneId.systemDefault()));
        return e;
    }
}
