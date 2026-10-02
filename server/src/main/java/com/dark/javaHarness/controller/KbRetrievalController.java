package com.dark.javaHarness.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.KbRetrievalLogEntity;
import com.dark.javaHarness.mapper.KbRetrievalLogMapper;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 检索观测查询接口：按会话或全量查看每次知识检索的来源/查询/命中数/耗时/降级原因。
 *
 * <p>数据由 KbRetrievalRecorder 在 KnowledgeRetriever 检索出口异步写入（V34）。
 * GET /api/kb-retrievals?sessionId=xxx&amp;limit=50
 * （sessionId 可省略查全量，按 id 倒序）。
 */
@RestController
@RequestMapping("/api/kb-retrievals")
public class KbRetrievalController {

    /** 默认与最大返回条数（防全表拖取） */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final KbRetrievalLogMapper mapper;

    public KbRetrievalController(KbRetrievalLogMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public List<KbRetrievalLogEntity> list(@RequestParam(required = false) String sessionId,
                                           @RequestParam(defaultValue = "50") int limit) {
        int n = Math.min(Math.max(limit, 1), MAX_LIMIT);
        QueryWrapper<KbRetrievalLogEntity> qw = new QueryWrapper<>();
        if (sessionId != null && !sessionId.isBlank()) {
            qw.eq("session_id", sessionId);
        }
        qw.orderByDesc("id").last("LIMIT " + n);
        return mapper.selectList(qw);
    }
}
