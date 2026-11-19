package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.dto.ContextComposition;
import com.dark.javaHarness.prompt.ContextCompositionQueryService;
import com.dark.javaHarness.prompt.ContextCompositionStore;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话上下文构成快照查询（只读）：
 * <ul>
 *   <li>{@code GET /api/monitor/context?sessionId=...}：内存快照（30 分钟 TTL）优先，
 *       未命中回退 context_composition_log 最近一条；仍无则 404。</li>
 *   <li>{@code GET /api/monitor/context/history?sessionId=...&limit=20}：持久化历史（新→旧，limit 收敛 [1,100]）。</li>
 * </ul>
 * 只读快照/观测表，不重新组装 prompt、不触发 RAG。
 */
@RestController
@RequestMapping("/api/monitor")
public class ContextCompositionController {

    private final ContextCompositionStore store;
    private final ContextCompositionQueryService queryService;

    public ContextCompositionController(ContextCompositionStore store,
                                        ContextCompositionQueryService queryService) {
        this.store = store;
        this.queryService = queryService;
    }

    @GetMapping("/context")
    public ResponseEntity<ContextComposition> get(@RequestParam(required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return store.get(sessionId)
                .or(() -> queryService.latest(sessionId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/context/history")
    public List<ContextComposition> history(@RequestParam(required = false) String sessionId,
                                            @RequestParam(defaultValue = "20") int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return queryService.history(sessionId, limit);
    }
}
