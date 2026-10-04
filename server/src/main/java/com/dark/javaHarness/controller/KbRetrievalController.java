package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.entity.KbRetrievalLogEntity;
import com.dark.javaHarness.service.ObserveQueryService;
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
 * 查询逻辑收口在 {@link ObserveQueryService}（Controller 不直连 mapper）。
 */
@RestController
@RequestMapping("/api/kb-retrievals")
public class KbRetrievalController {

    private final ObserveQueryService observeQueryService;

    public KbRetrievalController(ObserveQueryService observeQueryService) {
        this.observeQueryService = observeQueryService;
    }

    @GetMapping
    public List<KbRetrievalLogEntity> list(@RequestParam(required = false) String sessionId,
                                           @RequestParam(defaultValue = "50") int limit) {
        return observeQueryService.listKbRetrievals(sessionId, limit);
    }
}
