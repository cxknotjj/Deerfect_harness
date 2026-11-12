package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.dto.ContextComposition;
import com.dark.javaHarness.prompt.ContextCompositionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话上下文构成快照查询（只读）：
 * GET /api/monitor/context?sessionId=... —— 命中返回最近一次请求的构成 200，无快照 404。
 * 只读内存快照，不重新组装 prompt、不触发 RAG、不访问数据库；进程重启后快照清空。
 */
@RestController
@RequestMapping("/api/monitor")
public class ContextCompositionController {

    private final ContextCompositionStore store;

    public ContextCompositionController(ContextCompositionStore store) {
        this.store = store;
    }

    @GetMapping("/context")
    public ResponseEntity<ContextComposition> get(@RequestParam(required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return store.get(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
