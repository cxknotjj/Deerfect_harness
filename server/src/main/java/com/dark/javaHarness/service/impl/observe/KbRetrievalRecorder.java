package com.dark.javaHarness.service.impl.observe;

import com.dark.javaHarness.domain.KbRetrievalLog;
import com.dark.javaHarness.domain.entity.KbRetrievalLogEntity;
import com.dark.javaHarness.mapper.KbRetrievalLogMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * RAG 检索观测记录器：把每次知识检索（预取三提交点 / 执行期现查 / 缓存短路 / 降级）
 * 异步写入 kb_retrieval_log 表（V34，spec: add-kb-retrieval-log）。
 *
 * <p>设计约束对齐 {@link LlmCallRecorder} 先例：<b>观测永不影响主链路</b>——
 * 落库走 boundedElastic 边界异步执行，任何异常（表缺失/DB 抖动）只记 warn 单行，
 * 不向调用方传播；recorder 缺位（null）时调用方零行为（单测/未装配场景）。
 */
@Service
public class KbRetrievalRecorder {

    private static final Logger log = LoggerFactory.getLogger(KbRetrievalRecorder.class);

    private final KbRetrievalLogMapper mapper;

    public KbRetrievalRecorder(KbRetrievalLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 异步落库一条检索记录；立即返回，内部异常仅 warn */
    public void record(KbRetrievalLog log1) {
        Mono.fromRunnable(() -> doInsert(log1))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> { },
                        e -> log.warn("[kb-retrieval] 观测记录落库失败（不影响主链路）：{}", e.getMessage()));
    }

    private void doInsert(KbRetrievalLog c) {
        KbRetrievalLogEntity e = new KbRetrievalLogEntity();
        e.setSessionId(c.sessionId());
        e.setAgentName(c.agentName());
        e.setSource(c.source());
        e.setQuery(truncate(c.query(), 500));
        e.setKbs(c.kbs());
        e.setHitCount(c.hitCount());
        e.setDurationMs(c.durationMs());
        e.setOk(c.ok() ? 1 : 0);
        e.setErrorMsg(truncate(c.errorMsg(), 500));
        e.setTurnId(c.turnId());
        e.setTraceId(c.traceId());
        e.setStartedAt(c.startedAt());
        e.setCreatedAt(LocalDateTime.now());
        mapper.insert(e);
    }

    /** 库列超长截断防写入失败（query VARCHAR(512)/error_msg VARCHAR(512)，留余量按 500 截） */
    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
