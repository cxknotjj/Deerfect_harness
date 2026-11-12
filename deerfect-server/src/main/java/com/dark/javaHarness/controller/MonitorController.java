package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.dto.MonitorSnapshot;
import com.dark.javaHarness.observability.MonitorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行监控：全部渠道的会话、LLM 调用（首 token/耗时/token）、工具调用与小时趋势。
 * GET /api/monitor?hours=24&amp;agent=nailong&amp;channel=qq&amp;limit=50（agent/channel 均可省略 = 不过滤）。
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping
    public MonitorSnapshot get(@RequestParam(defaultValue = "24") int hours,
                               @RequestParam(required = false) String agent,
                               @RequestParam(required = false) String channel,
                               @RequestParam(defaultValue = "50") int limit) {
        return monitorService.get(hours, agent, channel, limit);
    }
}
