package com.dark.javaHarness.llm.catalog;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.mapper.AgentMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 配置目录：按 Agent 名读取启用中的 Agent。
 *
 * <p>所有 LLM 调用都从 Agent 身份出发，这是唯一入口；未命中直接报错，不回退默认。
 * 首次访问时懒加载，配置热更新时调用 {@link #reload()}。
 */
@Component
public class AgentCatalog {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalog.class);

    private final AgentMapper agentMapper;

    private volatile Map<String, AgentEntity> byName = Map.of();

    public AgentCatalog(AgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    /** 按名称取启用中的 Agent；不存在或已禁用直接抛错。 */
    public AgentEntity require(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName 不能为空");
        }
        ensureLoaded();
        AgentEntity agent = byName.get(agentName);
        if (agent == null) {
            throw new IllegalArgumentException("Agent 不存在或未启用: " + agentName);
        }
        return agent;
    }

    /** 重新读取 agent 表并整体替换映射。 */
    public synchronized void reload() {
        List<AgentEntity> rows = agentMapper.selectList(
                new QueryWrapper<AgentEntity>().eq("status", 1));
        Map<String, AgentEntity> fresh = new HashMap<>();
        if (rows != null) {
            for (AgentEntity row : rows) {
                fresh.put(row.getAgentName(), row);
            }
        }
        this.byName = Map.copyOf(fresh);
        log.info("[llm] AgentCatalog 已加载 {} 个启用 Agent", fresh.size());
    }

    private void ensureLoaded() {
        if (byName.isEmpty()) {
            reload();
        }
    }
}
