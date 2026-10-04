package com.dark.javaHarness.service.impl.observe;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.KbRetrievalLogEntity;
import com.dark.javaHarness.domain.entity.LlmCallLogEntity;
import com.dark.javaHarness.domain.entity.ToolCallLogEntity;
import com.dark.javaHarness.mapper.KbRetrievalLogMapper;
import com.dark.javaHarness.mapper.LlmCallLogMapper;
import com.dark.javaHarness.mapper.ToolCallLogMapper;
import com.dark.javaHarness.service.ObserveQueryService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 观测日志查询实现：三张观测表（V3 llm_call_log / V15 tool_call_log / V34 kb_retrieval_log）
 * 共用一套过滤与截断口径——可选条件为空查全量、按 id 倒序、limit 钳制 [1, 200]。
 */
@Service
public class ObserveQueryServiceImpl implements ObserveQueryService {

    /** 返回条数上限（防全表拖取），下限 1 */
    private static final int MAX_LIMIT = 200;

    private final LlmCallLogMapper llmCallLogMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final KbRetrievalLogMapper kbRetrievalLogMapper;

    public ObserveQueryServiceImpl(LlmCallLogMapper llmCallLogMapper,
                                   ToolCallLogMapper toolCallLogMapper,
                                   KbRetrievalLogMapper kbRetrievalLogMapper) {
        this.llmCallLogMapper = llmCallLogMapper;
        this.toolCallLogMapper = toolCallLogMapper;
        this.kbRetrievalLogMapper = kbRetrievalLogMapper;
    }

    @Override
    public List<LlmCallLogEntity> listLlmCalls(String sessionId, int limit) {
        QueryWrapper<LlmCallLogEntity> qw = new QueryWrapper<>();
        if (hasText(sessionId)) {
            qw.eq("session_id", sessionId);
        }
        qw.orderByDesc("id").last("LIMIT " + clamp(limit));
        return llmCallLogMapper.selectList(qw);
    }

    @Override
    public List<ToolCallLogEntity> listToolCalls(String sessionId, String serverName, int limit) {
        QueryWrapper<ToolCallLogEntity> qw = new QueryWrapper<>();
        if (hasText(sessionId)) {
            qw.eq("session_id", sessionId);
        }
        if (hasText(serverName)) {
            qw.eq("server_name", serverName);
        }
        qw.orderByDesc("id").last("LIMIT " + clamp(limit));
        return toolCallLogMapper.selectList(qw);
    }

    @Override
    public List<KbRetrievalLogEntity> listKbRetrievals(String sessionId, int limit) {
        QueryWrapper<KbRetrievalLogEntity> qw = new QueryWrapper<>();
        if (hasText(sessionId)) {
            qw.eq("session_id", sessionId);
        }
        qw.orderByDesc("id").last("LIMIT " + clamp(limit));
        return kbRetrievalLogMapper.selectList(qw);
    }

    /** null / 空白视为未传（查全量） */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** limit 收敛到 [1, 200] */
    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }
}
