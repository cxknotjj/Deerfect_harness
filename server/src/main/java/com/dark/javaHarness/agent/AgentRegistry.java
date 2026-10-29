package com.dark.javaHarness.agent;

import com.dark.javaHarness.agent.agentic.AgentScopeModelFactory;
import com.dark.javaHarness.config.AgenticProperties;
import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.observability.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.prompt.ToolLazyManager;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Agent 表驱动注册表：启动时把 agent 表全部对话 Agent 行（is_internal=0）注册为
 * GeneralAssistantAgent 实例（name=行 agentName）；运行时路由未命中再查表惰性注册，
 * 运行中插行免重启即生效。
 *
 * <p>查表仅依赖 Dao 层 {@link AgentConfigProvider#listAgentNames()}（只返回 is_internal=0
 * 行名，内部角色排除由字段驱动）；AgentService 仅作为 GA 构造参数经 ObjectProvider
 * 透传（GA 内部按 agentName 查配置的现实依赖），本类与其零方法调用，无循环依赖。
 *
 * <p>配置优先级单一事实来源：GA 实例每次调用按 agentName 查 agent 表——行存在即 DB
 * 配置生效，行缺失走 GA 内置默认 prompt + 默认模型，注册表不区分两种来源、不合并。
 *
 * <p>multi-agent 编排执行体（MultiAgentGraphAgent bean）经 {@link #register} 预注入，
 * 不由表行构造。fail-safe：启动逐行注册 try-catch，单行脏数据（行名空白/构造异常）
 * warn 跳过不阻断启动；惰性注册查表未命中/构造失败按「未命中」处理，抛「未知 Agent」
 * 含可用列表，不向请求方泄漏底层异常。
 */
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final AgentConfigProvider provider;
    private final ObjectProvider<AgentService> agentServiceProvider;
    private final ChatClientRegistry clientRegistry;
    private final SessionService memoryStore;
    private final ToolAssignments toolAssignments;
    private final LlmCallRecorder recorder;
    private final ContextBudgetProperties budgets;
    /** LLM 超时配置（app.chat.timeouts.*）：透传给 GA 内部调用器作流式空闲超时（null 走默认） */
    private final ChatTimeoutProperties timeouts;
    private final ToolLazyManager lazyTools;
    /** 共享 Prompt 组装器（含 skill 段提供者），注册实例与编排实例共用；null 时 GA 裸构建（测试场景） */
    private final PromptAssembler promptAssembler;
    /** skill 装配管理器（load_skill 元工具来源）；null 时不注册元工具（测试场景） */
    private final SkillManager skillManager;
    /** 知识检索器（RAG 注入面）；null 时零行为变化（知识库禁用/测试场景） */
    private final com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever;
    /** Agentic SIMPLE 路径配置（app.agentic.*）；null = 未启用（测试/旧装配），全部走 ChatClient 路径 */
    private final AgenticProperties agenticProperties;
    /** AgentScope 模型工厂；null = 未启用 AgentScope 路径 */
    private final AgentScopeModelFactory agentScopeModelFactory;

    /** 已注册路由表（agentName → 实例）；computeIfAbsent 保证并发惰性注册单次构造 */
    private final ConcurrentHashMap<String, Agent> agents = new ConcurrentHashMap<>();

    /** 兼容构造（旧测试/调用链）：promptAssembler/skillManager 为 null，GA 内部裸构建、不注册 load_skill */
    public AgentRegistry(AgentConfigProvider provider,
                         ObjectProvider<AgentService> agentServiceProvider,
                         ChatClientRegistry clientRegistry,
                         SessionService memoryStore,
                         ToolAssignments toolAssignments,
                         LlmCallRecorder recorder,
                         ContextBudgetProperties budgets,
                         ToolLazyManager lazyTools) {
        this(provider, agentServiceProvider, clientRegistry, memoryStore, toolAssignments,
                recorder, budgets, lazyTools, null, null);
    }

    /** 全参构造：promptAssembler 为共享 bean（含 skill 段提供者），skillManager 供 GA 注册 load_skill 元工具 */
    public AgentRegistry(AgentConfigProvider provider,
                         ObjectProvider<AgentService> agentServiceProvider,
                         ChatClientRegistry clientRegistry,
                         SessionService memoryStore,
                         ToolAssignments toolAssignments,
                         LlmCallRecorder recorder,
                         ContextBudgetProperties budgets,
                         ToolLazyManager lazyTools,
                         PromptAssembler promptAssembler,
                         SkillManager skillManager) {
        this(provider, agentServiceProvider, clientRegistry, memoryStore, toolAssignments,
                recorder, budgets, lazyTools, promptAssembler, skillManager, null, null);
    }

    /** 全参构造（含 RAG 知识检索）：knowledgeRetriever 仅知识库启用时非 null（ChatAgentConfig 经 ObjectProvider 注入） */
    public AgentRegistry(AgentConfigProvider provider,
                         ObjectProvider<AgentService> agentServiceProvider,
                         ChatClientRegistry clientRegistry,
                         SessionService memoryStore,
                         ToolAssignments toolAssignments,
                         LlmCallRecorder recorder,
                         ContextBudgetProperties budgets,
                         ToolLazyManager lazyTools,
                         PromptAssembler promptAssembler,
                         SkillManager skillManager,
                         com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever,
                         ChatTimeoutProperties timeouts) {
        this(provider, agentServiceProvider, clientRegistry, memoryStore, toolAssignments, recorder,
                budgets, lazyTools, promptAssembler, skillManager, knowledgeRetriever, timeouts,
                null, null);
    }

    /**
     * 全参构造（含 Agentic 路径）：agenticProperties.enabled 且 modelFactory 非空时，
     * 可路由对话 Agent 由 {@link AgentScopeGeneralAgent}（ReAct）承载，ChatClient 实例作为回退。
     * Agentic 链路包含「判定为 SIMPLE 后由 general 执行」与「显式指定 Agent 直答」两种形态，
     * 它们都是单 Agent 路径；Graph 编排（multi-agent）由预注入实例承载，不走 ReAct。
     */
    public AgentRegistry(AgentConfigProvider provider,
                         ObjectProvider<AgentService> agentServiceProvider,
                         ChatClientRegistry clientRegistry,
                         SessionService memoryStore,
                         ToolAssignments toolAssignments,
                         LlmCallRecorder recorder,
                         ContextBudgetProperties budgets,
                         ToolLazyManager lazyTools,
                         PromptAssembler promptAssembler,
                         SkillManager skillManager,
                         com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever,
                         ChatTimeoutProperties timeouts,
                         AgenticProperties agenticProperties,
                         AgentScopeModelFactory agentScopeModelFactory) {
        this.provider = provider;
        this.agentServiceProvider = agentServiceProvider;
        this.clientRegistry = clientRegistry;
        this.memoryStore = memoryStore;
        this.toolAssignments = toolAssignments;
        this.recorder = recorder;
        this.budgets = budgets;
        this.lazyTools = lazyTools;
        this.promptAssembler = promptAssembler;
        this.skillManager = skillManager;
        this.knowledgeRetriever = knowledgeRetriever;
        this.timeouts = timeouts;
        this.agenticProperties = agenticProperties;
        this.agentScopeModelFactory = agentScopeModelFactory;
    }

    /** 启动注册：注册 agent 表全部对话 Agent 行（is_internal=0），逐行容错；general 行缺失时代码兜底注册。
     *  已存在的实例（预注入的 Graph 编排 bean）不再被表行覆盖——防止数据误配把 multi-agent 换成对话 Agent。 */
    public void init() {
        for (String name : provider.listAgentNames()) {
            if (name == null || name.isBlank()) {
                log.warn("[agent注册] agent 表存在空白行名，跳过");
                continue;
            }
            if (agents.containsKey(name)) {
                log.info("[agent注册] agent='{}' 已由预注入实例注册，跳过表行覆盖", name);
                continue;
            }
            try {
                register(createGeneralAssistant(name));
            } catch (Exception e) {
                log.warn("[agent注册] 启动注册失败，跳过 agent='{}'", name, e);
            }
        }
        // 兜底：general 行缺失时注册代码兜底实例（内置默认 prompt + 默认模型），保证默认 Agent 始终可路由
        if (!agents.containsKey(AgentConstants.DEFAULT_AGENT)) {
            register(createGeneralAssistant(AgentConstants.DEFAULT_AGENT));
            log.warn("[agent注册] agent 表缺 general 行，已用代码兜底配置注册");
        }
        log.info("[agent注册] 启动注册完成，可路由 Agent: {}", agents.keySet());
    }

    /** 注册已构造实例（multi-agent 编排 bean 等预注入场景）；同名重复注册以新实例覆盖。 */
    public void register(Agent agent) {
        agents.put(agent.name(), agent);
    }

    /**
     * 按名取 Agent：命中直接返回；未命中查表惰性注册（computeIfAbsent 原子构造，并发同名
     * 请求仅构造一次）。仅 is_internal=0 的行可注册；查表异常/行不存在/构造失败均按
     * 「未命中」处理（warn 后抛「未知 Agent」含可用列表）。
     */
    public Agent require(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            throw unknownAgent(agentName);
        }
        Agent agent = agents.get(agentName);
        if (agent != null) {
            return agent;
        }
        // listAgentNames 只含 is_internal=0 行名：内部角色行/不存在的行/查表异常都走未命中
        if (!provider.listAgentNames().contains(agentName)) {
            log.warn("[agent注册] 路由未命中且 agent 表无可注册对话 Agent 行 agent='{}'", agentName);
            throw unknownAgent(agentName);
        }
        return agents.computeIfAbsent(agentName, this::createRoutable);
    }

    /** 已注册 Agent 名快照（只读副本）。 */
    public Set<String> agentNames() {
        return Set.copyOf(agents.keySet());
    }

    /** 惰性构造：包 try-catch 收敛底层异常（computeIfAbsent 映射内抛出则不落表，后续可重试）。 */
    private Agent createRoutable(String agentName) {
        try {
            return createGeneralAssistant(agentName);
        } catch (Exception e) {
            log.warn("[agent注册] 惰性构造 Agent 失败 agent='{}'", agentName, e);
            throw unknownAgent(agentName);
        }
    }

    /** 构造对话 Agent 实例：依赖全部来自本类构造注入字段，AgentService 经 ObjectProvider 现取透传。
     *  ReAct 装配到全部可路由对话 Agent（单 Agent 直答形态），Graph 编排 bean 走预注入不受影响。 */
    private Agent createGeneralAssistant(String agentName) {
        Agent chatClientAgent = new GeneralAssistantAgent(agentName, clientRegistry, memoryStore,
                agentServiceProvider.getObject(), toolAssignments, recorder, budgets, lazyTools,
                promptAssembler, skillManager, knowledgeRetriever, timeouts);
        if (agenticProperties == null || !agenticProperties.isEnabled() || agentScopeModelFactory == null) {
            return chatClientAgent;
        }
        return new AgentScopeGeneralAgent(agentName, clientRegistry, agentServiceProvider.getObject(),
                memoryStore, toolAssignments, recorder, budgets, lazyTools, promptAssembler,
                skillManager, knowledgeRetriever, timeouts, agentScopeModelFactory, agenticProperties,
                chatClientAgent);
    }

    /** 统一「未知 Agent」文案：含可用列表，不泄漏底层异常。 */
    private IllegalArgumentException unknownAgent(String agentName) {
        return new IllegalArgumentException("未知 Agent: " + agentName + "，可用: " + agents.keySet());
    }
}
