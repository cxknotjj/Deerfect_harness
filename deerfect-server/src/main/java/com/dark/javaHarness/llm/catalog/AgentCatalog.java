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

    /** 当前所有启用中的 Agent（校验与展示用）。 */
    public java.util.Collection<AgentEntity> all() {
        ensureLoaded();
        return byName.values();
    }

    /** 重新读取 agent 表并整体替换映射。 */
    public synchronized void reload() {
        replace(loadEnabled());
        log.info("[llm] AgentCatalog 已加载 {} 个启用 Agent", byName.size());
    }

    /** 当前内存快照（热更新失败时回滚用）。 */
    public Map<String, AgentEntity> snapshot() {
        return byName;
    }

    /** 用给定快照替换当前映射（热更新校验失败回滚 / 两阶段加载用）。 */
    public synchronized void replace(Map<String, AgentEntity> fresh) {
        this.byName = Map.copyOf(fresh);
    }

    /** 从库读取启用 Agent（不修改内存状态）。 */
    public Map<String, AgentEntity> loadEnabled() {
        List<AgentEntity> rows = agentMapper.selectList(
                new QueryWrapper<AgentEntity>().eq("status", 1));
        Map<String, AgentEntity> fresh = new HashMap<>();
        if (rows != null) {
            for (AgentEntity row : rows) {
                fresh.put(row.getAgentName(), row);
            }
        }
        return fresh;
    }

    private void ensureLoaded() {
        if (byName.isEmpty()) {
            reload();
        }
    }
}
