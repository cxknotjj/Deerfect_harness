package com.dark.javaHarness.agent.orchestrate;

import com.dark.javaHarness.agent.Agent;
import com.dark.javaHarness.agent.AgentChatCaller;
import com.dark.javaHarness.agent.LlmRetry;
import com.dark.javaHarness.agent.orchestrate.engine.OrchestrationEngine;
import com.dark.javaHarness.agent.orchestrate.engine.ParallelOrchestrationEngine;
import com.dark.javaHarness.agent.orchestrate.persistence.OrchestrationCheckpointStore;
import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.prompt.ToolLazyManager;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.observe.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 多 Agent 编排器：路径 B（复杂请求）的执行体。
 *
 * <p>编排「Lead 拆解 → 并行子任务 → 聚合」三阶段，每个阶段都是一次独立的 ChatClient
 * 单次调用（复用 {@link ChatClientRegistry} 的客户端），配置（模型 + 提示词）均取自
 * agent 表对应角色行：
 * - lead 阶段：查 {@code lead} 行（无则内置兜底），把复杂目标拆成至多 {@link #MAX_SUBTASKS} 条子任务
 *   （{@link LeadOutputParser} 解析），并为每条子任务指派专家（researcher/coder/analyst/writer/wms/general，
 *   白名单校验，非法回退）
 * - subtask 阶段：并行执行，按指派的专家名查 agent 表配置取对应 ChatClient 产出该子任务结果
 * - aggregate 阶段：查 {@code aggregator} 行（无则内置兜底），收集各子任务结果汇总成最终回答
 *
 * <p>与路径 A 的 {@link GeneralAssistantAgent} 对 Key 契约一致：
 * {@link #execute(Goal)} 返回最终回答 String；Goal 生命周期与会话记忆写回
 * 统一由 AgentService / ChatService 负责。
 *
 * <p>执行体为自研 {@link ParallelOrchestrationEngine}（替代 SAA StateGraph）：
 * 同步执行直接取结果；流式执行经 {@link MultiAgentStreamPipeline} 把引擎事件映射为
 * SSE 行（进度行原样透传、聚合 token 逐字发射）；断点续跑按检查点阶段跳过已完成部分。
 * 编排预算熔断与 lead 产物解析分别委托 {@link OrchestrationBudget}/{@link LeadOutputParser}；
 * 兜底提示词与聚合 prompt 拼接委托 {@link OrchestrationPrompts}；三节点实现与节点→caller 桥接、
 * 流式聚合护栏委托 {@link OrchestrationNodes}。
 */
public class MultiAgentGraphAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentGraphAgent.class);

    /** 拆解子任务数兜底常量（生效上限见 OrchestrationNodes.resolvedMaxSubtasks） */
    static final int MAX_SUBTASKS = 4;

    /** 节点名常量（旧图拓扑遗留：进度行与日志口径引用） */
    static final String NODE_LEAD = "lead";
    static final String NODE_AGGREGATE = "aggregate";

    /** 状态键（节点实现类 OrchestrationNodes 与引擎跨类引用的契约） */
    public static final String K_OBJECTIVE = "objective";
    public static final String K_SESSION_ID = "sessionId";
    /** 轨迹键：轮次/调用链标识（引擎从 Goal 注入；编排内 5 次调用共用） */
    public static final String K_TURN_ID = "turnId";
    public static final String K_TRACE_ID = "traceId";
    /** 轨迹键：lead 调用的 span_id（lead 节点发起调用前生成写入；subtask/aggregate 读出作 parent_span） */
    public static final String K_LEAD_SPAN = "leadSpanId";
    public static final String K_SUBTASK_COUNT = "subtaskCount";
    public static final String K_SUBTASK_PREFIX = "subtask_";
    public static final String K_SUBTASK_AGENT_PREFIX = "subtaskAgent_";
    /** 任务书键：lead 出口写入（已按预算截断），subtask 节点读出作 user 文本；旧 checkpoint 缺键 orElse(null) 安全 */
    public static final String K_SUBTASK_BRIEF_PREFIX = "subtaskBrief_";
    /** 工具包键：lead 出口写入包名 CSV（空名单写空串），subtask 节点读出逐包解析包定义；旧 checkpoint 缺键 orElse("") 与空串同口径=无包 */
    public static final String K_SUBTASK_PACKS_PREFIX = "subtaskPacks_";
    public static final String K_RESULT_PREFIX = "result_";
    public static final String K_FINAL = "final";

    /** 子任务节点名前缀 */
    public static final String SUBTASK_NODE_PREFIX = "subtask-";

    /**
     * 预算超限跳过的子任务占位 result：聚合据此识别未执行子任务数（排除出真实结果），
     * 并在 prompt 注入降级说明；非空保证状态键有值、与「子任务失败无结果」区分。
     */
    public static final String SKIPPED_RESULT = "（预算超限跳过：编排 token 消费已达上限）";

    /**
     * 子任务墙钟超时占位 result：与 {@link #SKIPPED_RESULT} 同构的占位通道（聚合排除出
     * 真实结果、prompt 注入超时说明），文案区分跳过原因（超时 ≠ 预算超限）。
     */
    public static final String TIMEOUT_SKIPPED_RESULT = "（子任务执行超时，已跳过）";

    /**
     * 子任务执行失败占位 result：与 {@link #TIMEOUT_SKIPPED_RESULT} 同构的第三条占位通道
     * （模型调用异常/重试耗尽/看门狗中止等）。失败不向编排上抛——单子任务异常会让
     * 其他子任务的真实结果全部作废（2026-10-02）。
     */
    public static final String FAILED_RESULT = "（子任务执行失败，已跳过）";

    private final String agentName;
    /** 编排消费上限熔断：各节点共享的预算账本（依赖 {@link #budgets}；节点实现类亦引用） */
    private final OrchestrationBudget orchestrationBudget;
    /** 编排三节点实现（lead/subtask/aggregate + predict* 桥接 + 流式聚合护栏） */
    private final OrchestrationNodes nodes;
    /** 上下文预算配置（lead/聚合静态 prompt 预算、子任务并发；null 时用内置默认值，单测场景） */
    private final ContextBudgetProperties budgets;
    /** 流式管道：引擎事件 → SSE 行（进度行/token 原样透传） */
    private final MultiAgentStreamPipeline streamPipeline;
    /** 自研编排引擎：同步执行、流式执行与断点续跑的唯一执行体 */
    private final OrchestrationEngine engine;

    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder, null, null);
    }

    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                OrchestrationCheckpointStore checkpointStore) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder, checkpointStore, null);
    }

    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                OrchestrationCheckpointStore checkpointStore,
                                ContextBudgetProperties budgets) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder, checkpointStore, budgets, null);
    }

    /**
     * @param memoryStore 会话记忆源（SessionService，与路径 A GeneralAssistantAgent 同源同口径）：
     *                    lead 拆解节点据此注入会话记忆，null 时不注入（单测场景）
     */
    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                OrchestrationCheckpointStore checkpointStore,
                                ContextBudgetProperties budgets,
                                SessionService memoryStore) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder,
                checkpointStore, budgets, memoryStore, null, null, null, null, null);
    }

    /**
     * 兼容重载（不接 RAG 预取器）：既有调用方/单测直接构造时预取钩子零行为，
     * 与知识库禁用（ragPrefetcher bean 缺位）同语义；工具包解析同取零行为（provider=null）。
     */
    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                OrchestrationCheckpointStore checkpointStore,
                                ContextBudgetProperties budgets,
                                SessionService memoryStore,
                                ToolLazyManager lazyTools,
                                PromptAssembler promptAssembler,
                                SkillManager skillManager,
                                com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever,
                                ChatTimeoutProperties timeouts) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder,
                checkpointStore, budgets, memoryStore, lazyTools, promptAssembler,
                skillManager, knowledgeRetriever, timeouts, null, null);
    }

    /**
     * 兼容重载（不接工具包定义读取器）：既有调用方/单测直接构造时工具包挂载零行为
     * （子任务 toolPacks 声明被忽略、只按模板自身工具面执行），对齐 ragPrefetcher 先例。
     */
    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                OrchestrationCheckpointStore checkpointStore,
                                ContextBudgetProperties budgets,
                                SessionService memoryStore,
                                ToolLazyManager lazyTools,
                                PromptAssembler promptAssembler,
                                SkillManager skillManager,
                                com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever,
                                ChatTimeoutProperties timeouts,
                                com.dark.javaHarness.service.impl.route.RagPrefetcher ragPrefetcher) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder,
                checkpointStore, budgets, memoryStore, lazyTools, promptAssembler,
                skillManager, knowledgeRetriever, timeouts, ragPrefetcher, null);
    }

    /**
     * 全参构造（含 skill 装配、RAG 知识检索与工具包定义读取）：knowledgeRetriever 仅知识库启用时非 null
     * （ChatAgentConfig 经 ObjectProvider 注入），透传给编排调用器后 lead/各子任务按
     * 各自 user 文本检索（aggregator 由检索器角色策略跳过）。timeouts 透传给编排调用器
     * 作流式空闲超时（null 时走调用器默认，见 AgentChatCaller.DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS）。
     * ragPrefetcher 与 knowledgeRetriever 同条件装配（可 null=知识库禁用）：非 null 时
     * 子任务节点入口经其异步提交 RAG 预取（把检索延迟藏进子任务排队/执行的等待窗口），
     * null 时预取钩子零行为。
     * agentConfigProvider 为工具包定义读取器（子任务 toolPacks 挂载解析：
     * 包工具并入请求工具面 + 包纪律段拼入 user 文本；可 null=包解析零行为，
     * 对齐 ragPrefetcher 先例）。
     * checkpointStore 为引擎检查点存储（可 null = 不落库且拒绝续跑，单测场景）。
     */
    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                OrchestrationCheckpointStore checkpointStore,
                                ContextBudgetProperties budgets,
                                SessionService memoryStore,
                                ToolLazyManager lazyTools,
                                PromptAssembler promptAssembler,
                                SkillManager skillManager,
                                com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever,
                                ChatTimeoutProperties timeouts,
                                com.dark.javaHarness.service.impl.route.RagPrefetcher ragPrefetcher,
                                com.dark.javaHarness.service.AgentConfigProvider agentConfigProvider) {
        ToolLazyManager lazy = lazyTools != null ? lazyTools : new ToolLazyManager(toolAssignments, false);
        this.agentName = agentName;
        // 工具索引段与延迟加载同源：开启时索引段追加 expand_tool 使用引导（与轻量态工具面对齐）
        PromptAssembler assembler = promptAssembler != null ? promptAssembler
                : new PromptAssembler(agentService, toolAssignments, List.of(), List.of(), lazy.isEnabled());
        AgentChatCaller chatCaller = new AgentChatCaller(clientRegistry, agentService, toolAssignments, recorder,
                new LlmRetry(), budgets, assembler, memoryStore, lazy, skillManager,
                knowledgeRetriever, timeouts);
        this.budgets = budgets != null ? budgets : new ContextBudgetProperties();
        this.orchestrationBudget = new OrchestrationBudget(this.budgets);
        this.nodes = new OrchestrationNodes(chatCaller, assembler, this.budgets, this.orchestrationBudget,
                ragPrefetcher, agentConfigProvider);
        this.engine = new ParallelOrchestrationEngine(this.nodes, this.orchestrationBudget,
                checkpointStore, this.budgets.getSubtaskConcurrency());
        this.streamPipeline = new MultiAgentStreamPipeline();
    }

    @Override
    public String name() {
        return agentName;
    }

    /** 执行复杂目标：委托自研并行引擎（lead → 并行子任务 → 聚合），返回最终回答。 */
    @Override
    public String execute(Goal goal) {
        log.info("[multi-agent] 开始编排复杂目标: {}", goal.objective());
        return engine.execute(goal);
    }

    /**
     * 流式执行复杂目标：引擎事件 → SSE 行（进度行与聚合 token 原样透传）。
     *
     * <p>子任务保持阻塞调用：多子任务并行执行，token 直推会交错乱序；用户体感关键
     * 在最终回答的打字机效果，由聚合阶段承担。lead 产出为 JSON 中间产物，不推送 token。
     */
    @Override
    public Flux<String> executeStreamReactive(Goal goal) {
        return streamPipeline.run(engine.stream(goal));
    }

    /**
     * 断点续跑：按该 goal 最近检查点的阶段继续，已完成阶段不再重跑。
     * DONE → 零调用回放最终回答；SUBTASKS_DONE → 只补聚合；LEAD_DONE → 补子任务与聚合。
     *
     * <p>校验在方法调用时同步完成（快速失败）：
     * 未启用检查点 / 无该 goal 的检查点记录时抛 {@link IllegalStateException}。
     *
     * <p>输出语义与 {@link #executeStreamReactive(Goal)} 完全一致（进度 + 打字机）。
     */
    @Override
    public Flux<String> resumeStreamReactive(Goal goal) {
        return streamPipeline.run(engine.resume(goal));
    }
}
