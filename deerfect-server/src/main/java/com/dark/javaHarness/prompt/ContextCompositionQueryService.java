package com.dark.javaHarness.prompt;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.dto.ContextComposition;
import com.dark.javaHarness.domain.entity.ContextCompositionLogEntity;
import com.dark.javaHarness.mapper.ContextCompositionLogMapper;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 上下文构成快照冷读（context_composition_log）：
 * {@code latest} 供接口在内存快照未命中（重启/超过 30 分钟）时回退最近一条；
 * {@code history} 按会话返回倒序快照列表（limit 收敛 [1,100]）。
 *
 * <p>parts 为自身写入的 JSON；损坏行只告警并跳过，不让单行坏数据打断查询。
 */
@Service
public class ContextCompositionQueryService {

    private static final Logger log = LoggerFactory.getLogger(ContextCompositionQueryService.class);
    private static final TypeReference<List<ContextComposition.Part>> PART_LIST =
            new TypeReference<>() {
            };

    static final int MAX_HISTORY = 100;

    private final ContextCompositionLogMapper mapper;
    private final ObjectMapper objectMapper;

    public ContextCompositionQueryService(ContextCompositionLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 该会话最近一条持久化快照；无行或行损坏返回 empty */
    public Optional<ContextComposition> latest(String sessionId) {
        List<ContextCompositionLogEntity> rows = mapper.selectList(new QueryWrapper<ContextCompositionLogEntity>()
                .eq("session_id", sessionId)
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        return toComposition(rows.get(0));
    }

    /** 该会话快照历史（新→旧）；坏行跳过 */
    public List<ContextComposition> history(String sessionId, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_HISTORY));
        List<ContextCompositionLogEntity> rows = mapper.selectList(new QueryWrapper<ContextCompositionLogEntity>()
                .eq("session_id", sessionId)
                .orderByDesc("id")
                .last("LIMIT " + size));
        List<ContextComposition> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (ContextCompositionLogEntity row : rows) {
            toComposition(row).ifPresent(out::add);
        }
        return out;
    }

    private Optional<ContextComposition> toComposition(ContextCompositionLogEntity row) {
        try {
            List<ContextComposition.Part> parts = objectMapper.readValue(row.getParts(), PART_LIST);
            long capturedAt = row.getCreatedAt() == null
                    ? 0L
                    : row.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            return Optional.of(new ContextComposition(
                    row.getSessionId(),
                    row.getAgentName(),
                    row.getModel(),
                    row.getTurnId(),
                    capturedAt,
                    row.getEstimatedTotalTokens() == null ? 0 : row.getEstimatedTotalTokens(),
                    parts));
        } catch (RuntimeException e) {
            log.warn("[context-composition] 快照行解析失败（id={}，已跳过）：{}", row.getId(), e.toString());
            return Optional.empty();
        }
    }
}
