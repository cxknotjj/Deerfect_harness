package com.dark.javaHarness.view;

import java.util.List;

/**
 * Agent 列表响应（GET /api/harness/agents）：条目携带数据库主键 id 与名称。
 */
public record AgentsView(List<AgentItemView> agents) {
}
