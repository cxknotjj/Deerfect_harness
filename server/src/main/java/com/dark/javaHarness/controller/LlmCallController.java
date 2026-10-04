package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.entity.LlmCallLogEntity;
import com.dark.javaHarness.service.ObserveQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 调用观测查询接口：按会话或全量查看每次 LLM 调用的耗时 / token 消耗。
 *
 * <p>数据由 LlmCallRecorder 在各调用出口（路径 A / 路径 B 各环节 / 路由判断）异步写入。
 * GET /api/llm-calls?sessionId=xxx&amp;limit=50（sessionId 可省略查全量，按时间倒序）。
 * 查询逻辑收口在 {@link ObserveQueryService}（Controller 不直连 mapper）。
 */
@RestController
@RequestMapping("/api/llm-calls")
public class LlmCallController {

    private final ObserveQueryService observeQueryService;

    public LlmCallController(ObserveQueryService observeQueryService) {
        this.observeQueryService = observeQueryService;
    }

    @GetMapping
    public List<LlmCallLogEntity> list(@RequestParam(required = false) String sessionId,
                                       @RequestParam(defaultValue = "50") int limit) {
        return observeQueryService.listLlmCalls(sessionId, limit);
    }
}
