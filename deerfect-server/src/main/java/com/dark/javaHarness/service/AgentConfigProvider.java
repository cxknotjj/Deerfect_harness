package com.dark.javaHarness.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dark.javaHarness.contract.model.AgentConfig;
import com.dark.javaHarness.view.AgentItemView;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.AgentMapper;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 配置读取器：负责从 agent 表读取 Agent 的运行配置与名称映射。
 * 将 AgentMapper 的数据访问从 AgentServiceImpl 拆出，职责单一，便于替换与测试。
 */
@Component
public class AgentConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigProvider.class);

    /**
     * 配置缓存 TTL（毫秒）：人工改 agent 表后的最大生效滞后。取值权衡——长一点省查询，
     * 短一点改提示词时更快看到效果；30s 对本地调提示词与线上都够用。
     */
    private static final long CONFIG_CACHE_TTL_MILLIS = 30_000L;

    private final AgentMapper agentMapper;
    private final ModelProviderMapper modelProviderMapper;

    /** 命中结果缓存（key=agentName）：配置是热路径上的只读小表，避免每条 LLM 调用两查 */
    private final Map<String, CachedConfig> configCache = new ConcurrentHashMap<>();

    /** 缓存条目：配置 + 过期时刻（epoch 毫秒） */
    private record CachedConfig(AgentConfig config, long expiresAt) {
    }

    public AgentConfigProvider(AgentMapper agentMapper, ModelProviderMapper modelProviderMapper) {
        this.agentMapper = agentMapper;
        this.modelProviderMapper = modelProviderMapper;
    }

    /**
     * 按 agentId 从 agent 表查询 agentName（CLI 传入 agentId 时用于路由映射）。
     *
     * <p>语义边界（优化审查 2026-11-03）：只有「行不存在」返回 empty；查询失败（DB 不可用）
     * 直接抛出。此前统一 catch 后返回 empty，会让绑定了专属 Agent 的会话静默回退到 general
     * ——跑错 Agent 比请求失败更难排查。
     */
    public Optional<String> findAgentNameById(Long agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        AgentEntity row = agentMapper.selectById(agentId);
        if (row != null) {
            log.debug("[agent映射] agentId={} 命中 agent_name='{}'", agentId, row.getAgentName());
            return Optional.of(row.getAgentName());
        }
        log.warn("[agent映射] agentId={} 在 agent 表无记录", agentId);
        return Optional.empty();
    }

    /**
     * 列出可注册为对话 Agent 的行名：仅返回 is_internal=0 的行（内部角色行 is_internal=1
     * 不返回，注册表据此排除 lead/aggregator/multi-agent 等编排内部角色）；
     * 行名 null/空白过滤，异常时返回空列表不抛出（与既有容错风格一致）。
     */
    public List<String> listAgentNames() {
        try {
            return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                            .eq(AgentEntity::getIsInternal, 0)).stream()
                    .map(AgentEntity::getAgentName)
                    .filter(name -> name != null && !name.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("列出 agent 表行名失败，返回空列表", e);
            return List.of();
        }
    }

    /**
     * 列出对外可见的 Agent 条目（id = agent 表主键 agent_id + name）：过滤条件与
     * {@link #listAgentNames()} 一致（is_internal=0、行名非空白），按 id 升序保证下拉顺序
     * 稳定；异常时返回空列表不抛出（与既有容错风格一致）。
     */
    public List<AgentItemView> listAgentItems() {
        try {
            return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                            .eq(AgentEntity::getIsInternal, 0)
                            .orderByAsc(AgentEntity::getAgentId)).stream()
                    .filter(row -> row.getAgentId() != null && row.getAgentName() != null && !row.getAgentName().isBlank())
                    .map(row -> new AgentItemView(row.getAgentId(), row.getAgentName()))
                    .toList();
        } catch (Exception e) {
            log.warn("列出 agent 表条目失败，返回空列表", e);
            return List.of();
        }
    }

    /**
     * 从 agent 表读取指定 Agent 的运行配置（部署模型 + 系统提示词 + 知识库绑定 + 思考显示开关；模型名经 model_provider 表解析）。
     *
     * <p>带进程内缓存（TTL 见 {@link #CONFIG_CACHE_TTL_MILLIS}）：本方法是全部真实 LLM 调用的
     * 唯一配置读取点，一次 4 子任务编排会命中 10+ 次，而 agent / model_provider 都是几百行、
     * 极少变的小表——每次两查没有收益（优化审查 2026-11-03）。改库后最多滞后一个 TTL 生效；
     * 新增 model_provider 行不受影响（新 id 必然未命中缓存）。只缓存命中结果：行不存在仍每次
     * 查库并告警，避免「补了 agent 行要等 TTL」。
     */
    public Optional<AgentConfig> getAgentConfig(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        CachedConfig cached = configCache.get(agentName);
        if (cached != null && cached.expiresAt() > now) {
            return Optional.of(cached.config());
        }
        AgentEntity row = agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getAgentName, agentName)
                .last("LIMIT 1"));
        if (row == null) {
            log.warn("[agent配置] agent 表无 agentName='{}' 记录，将使用默认配置", agentName);
            return Optional.empty();
        }
        String modelName = resolveModelName(row.getModelProviderId());
        AgentConfig cfg = new AgentConfig(
                row.getModelProviderId(),
                modelName,
                blankToNull(row.getPrompt()),
                blankToNull(row.getKnowledge()),
                row.getThinking() != null && row.getThinking() == 1);
        configCache.put(agentName, new CachedConfig(cfg, now + CONFIG_CACHE_TTL_MILLIS));
        log.debug("[agent配置] agentName='{}' -> modelProviderId={}, model={}, prompt={}, knowledge={}, thinking={}",
                agentName, cfg.modelProviderId(), cfg.model(), cfg.prompt(), cfg.knowledge(), cfg.thinking());
        return Optional.of(cfg);
    }

    /**
     * 从 agent 表读取指定 Agent 的 tools 列（工具分配数据化，prompt 动态加载·子项 2）：
     * 逗号分隔的组名/工具名声明。行不存在/列空白返回 empty（ToolAssignments 据此回退代码
     * 内置分配）；查询失败向上抛——工具面读不到时静默少给工具，等于让 Agent 降级作答。
     */
    public Optional<String> findAgentTools(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return Optional.empty();
        }
        AgentEntity row = agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getAgentName, agentName)
                .select(AgentEntity::getTools)
                .last("LIMIT 1"));
        return row == null ? Optional.empty() : Optional.ofNullable(row.getTools());
    }

    /**
     * 工具包定义（包名 = agent 表 is_internal=0 且 tools 列非空的行名）：单查询取
     * tools（工具名单 CSV）+ prompt（领域纪律段）+ is_internal（包资格判定）三列。
     * 行不存在 / is_internal != 0（或 NULL，视为非包）/ tools 拆分后无有效项 均返回 empty
     * （调用方跳过该包）；查询失败向上抛，理由同 {@link #findAgentTools}。
     */
    public Optional<ToolPackDef> findToolPack(String packName) {
        if (packName == null || packName.isBlank()) {
            return Optional.empty();
        }
        AgentEntity row = agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getAgentName, packName)
                .select(AgentEntity::getTools, AgentEntity::getPrompt, AgentEntity::getIsInternal)
                .last("LIMIT 1"));
        if (row == null || row.getIsInternal() == null || row.getIsInternal() != 0
                || row.getTools() == null || row.getTools().isBlank()) {
            return Optional.empty();
        }
        // tools CSV 拆分：按逗号拆、逐项 trim、丢弃空白项、保持声明顺序
        List<String> toolNames = new java.util.ArrayList<>();
        for (String raw : row.getTools().split(",")) {
            String token = raw.trim();
            if (!token.isEmpty()) {
                toolNames.add(token);
            }
        }
        if (toolNames.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ToolPackDef(List.copyOf(toolNames), blankToNull(row.getPrompt())));
    }

    /** 工具包定义值对象：toolNames 为工具名单（trim 去空白后保序），disciplinePrompt 为领域纪律段原文（可 null=无纪律段） */
    public record ToolPackDef(List<String> toolNames, String disciplinePrompt) {
    }

    /**
     * 按 model_provider.id 解析模型名（请求级 model 参数用）；id 空或行不存在返回 null（走默认）。
     * 查询失败向上抛：回退默认模型会让整轮对话悄悄换到另一个模型，事后无法从响应里看出。
     */
    private String resolveModelName(Long modelProviderId) {
        if (modelProviderId == null) {
            return null;
        }
        ModelProviderEntity mp = modelProviderMapper.selectById(modelProviderId);
        return mp == null ? null : mp.getModel();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}