package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.entity.ToolCallLogEntity;
import com.dark.javaHarness.service.ObserveQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具调用观测查询接口：按会话或全量查看每次工具执行的参数摘要 / 状态 / 耗时。
 *
 * <p>数据由 LlmCallRecorder 在 TracedToolCallback 执行出口异步写入；MCP 工具带来源
 * server 名（非 MCP 工具该列为空），server 故障时可按 serverName 过滤定位。
 * GET /api/tool-calls?sessionId=xxx&amp;serverName=tavily&amp;limit=50
 * （sessionId / serverName 可省略查全量，按 id 倒序）。
 * 查询逻辑收口在 {@link ObserveQueryService}（Controller 不直连 mapper）。
 */
@RestController
@RequestMapping("/api/tool-calls")
public class ToolCallController {

    private final ObserveQueryService observeQueryService;

    public ToolCallController(ObserveQueryService observeQueryService) {
        this.observeQueryService = observeQueryService;
    }

    @GetMapping
    public List<ToolCallLogEntity> list(@RequestParam(required = false) String sessionId,
                                        @RequestParam(required = false) String serverName,
                                        @RequestParam(defaultValue = "50") int limit) {
        return observeQueryService.listToolCalls(sessionId, serverName, limit);
    }
}
