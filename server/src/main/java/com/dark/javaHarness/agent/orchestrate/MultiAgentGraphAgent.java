package com.dark.javaHarness.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.internal.node.ParallelNode;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.prompt.ToolLazyManager;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 多 Agent 编排器：路径 B（复杂请求）的执行体。
 *
 * <p>基于 {@link StateGraph} 编排「Lead 拆解 → 并行子任务 → 聚合」三阶段，
 * 每个阶段都是一次独立的 ChatClient 单次调用（复用 {@link ChatClientRegistry} 的客户端），
 * 配置（模型 + 提示词）均取自 agent 表对应角色行：
 * - lead 节点：查 {@code lead} 行（无则内置兜底），把复杂目标拆成至多 {@link #MAX_SUBTASKS} 条子任务
 *   （{@link LeadOutputParser} 解析），并为每条子任务指派专家（researcher/coder/analyst/writer/general，
 *   白名单校验，非法回退）
 * - subtask-i 节点：并行执行，按指派的专家名查 agent 表配置取对应 ChatClient 产出该子任务结果
 * - aggregate 节点：查 {@code aggregator} 行（无则内置兜底），收集各子任务结果汇总成最终回答
 *
 * <p>与路径 A 的 {@link GeneralAssistantAgent} 对 Key 契约一致：
 * {@link #execute(Goal)} 返回最终回答 String；Goal 生命周期与会话记忆写回
 * 统一由 AgentService / ChatService 负责。
 *
 * <p>图拓扑只构建一次；同步执行 {@link #execute(Goal)} 走 invoke；
 * 流式执行 {@link #executeStreamReactive(Goal)} 走「stream 主干帧 + 生命周期钩子旁路」
 * 双通道管道（详见 {@link MultiAgentStreamPipeline}）；
 * 编排预算熔断与 lead 产物解析分别委托 {@link OrchestrationBudget}/{@link LeadOutputParser}；
 * 兜底提示词与聚合 prompt 拼接委托 {@link OrchestrationPrompts}——本类保留编排本体：
 * 图构建、执行/续跑入口、熔断/取消接线；三节点实现与节点→caller 桥接、
 * 流式聚合护栏委托 {@link OrchestrationNodes}（超长类拆分 2026-09-25）。
 */
public class MultiAgentGraphAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentGraphAgent.class);

    /** 单个总任务拆解的子任务数上限，避免滚雪球（节点实现类 OrchestrationNodes 亦引用） */
    static final int MAX_SUBTASKS = 4;

    /** 节点名常量 */
    static final String NODE_LEAD = "lead";
    static final String NODE_AGGREGATE = "aggregate";

    /** 状态键（节点实现类 OrchestrationNodes 与 MultiAgentStreamPipeline 跨类引用的契约） */
    static final String K_OBJECTIVE = "objective";
    static final String K_SESSION_ID = "sessionId";
    /** 轨迹键：轮次/调用链标识（inputOf 从 Goal 注入，随图输入进状态；编排内 5 次调用共用） */
    static final String K_TURN_ID = "turnId";
    static final String K_TRACE_ID = "traceId";
    /** 轨迹键：lead 调用的 span_id（lead 节点发起调用前生成写入；subtask/aggregate 读出作 parent_span） */
    static final String K_LEAD_SPAN = "leadSpanId";
    static final String K_SUBTASK_COUNT = "subtaskCount";
    static final String K_SUBTASK_PREFIX = "subtask_";
    static final String K_SUBTASK_AGENT_PREFIX = "subtaskAgent_";
    static final String K_RESULT_PREFIX = "result_";
    static final String K_FINAL = "final";

    /** 子任务节点名前缀 */
    static final String SUBTASK_NODE_PREFIX = "subtask-";

    /**
     * 预算超限跳过的子任务占位 result：聚合据此识别未执行子任务数（排除出真实结果），
     * 并在 prompt 注入降级说明；非空保证状态键有值、与「子任务失败无结果」区分。
     */
    static final String SKIPPED_RESULT = "（预算超限跳过：编排 token 消费已达上限）";

    private final String agentName;
    /** 编排消费上限熔断：各节点共享的预算账本（依赖 {@link #budgets}；节点实现类亦引用） */
    private final OrchestrationBudget orchestrationBudget;
    /** 编排三节点实现（lead/subtask/aggregate + predict* 桥接 + 流式聚合护栏） */
    private final OrchestrationNodes nodes;
    /** 图拓扑（构建一次）：同步执行缓存编译 {@link #graph}；流式执行每次带监听器重新编译 */
    private final StateGraph stateGraph;
    private final CompiledGraph graph;
    /**
     * 检查点存储器（可 null = 不启用断点续跑，如单测环境）：
     * 非 null 时每个 superstep 结束自动落库（threadId=goalId），
     * 支持 {@link #resumeStreamReactive(Goal)} 从断点继续（已完成节点不再重跑）。
     */
    private final BaseCheckpointSaver checkpointSaver;
    /** 上下文预算配置（lead/聚合静态 prompt 预算；null 时用内置默认值，单测场景） */
    private final ContextBudgetProperties budgets;
    /** 流式管道：主干帧 + 旁路合并（依赖 {@link #streamPipeline} 建图回调） */
    private final MultiAgentStreamPipeline streamPipeline;

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
                                BaseCheckpointSaver checkpointSaver) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder, checkpointSaver, null);
    }

    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                BaseCheckpointSaver checkpointSaver,
                                ContextBudgetProperties budgets) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder, checkpointSaver, budgets, null);
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
                                BaseCheckpointSaver checkpointSaver,
                                ContextBudgetProperties budgets,
                                SessionService memoryStore) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder,
                checkpointSaver, budgets, memoryStore, null, null, null, null, null);
    }

    /**
     * 全参构造（含 skill 装配与 RAG 知识检索）：knowledgeRetriever 仅知识库启用时非 null
     * （ChatAgentConfig 经 ObjectProvider 注入），透传给编排调用器后 lead/各子任务按
     * 各自 user 文本检索（aggregator 由检索器角色策略跳过）。timeouts 透传给编排调用器
     * 作流式空闲超时（null 时走调用器默认，见 AgentChatCaller.DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS）。
     */
    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                BaseCheckpointSaver checkpointSaver,
                                ContextBudgetProperties budgets,
                                SessionService memoryStore,
                                ToolLazyManager lazyTools,
                                PromptAssembler promptAssembler,
                                SkillManager skillManager,
                                com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever,
                                ChatTimeoutProperties timeouts) {
        ToolLazyManager lazy = lazyTools != null ? lazyTools : new ToolLazyManager(toolAssignments, false);
        this.agentName = agentName;
        // 工具索引段与延迟加载同源：开启时索引段追加 expand_tool 使用引导（与轻量态工具面对齐）
        PromptAssembler assembler = promptAssembler != null ? promptAssembler
                : new PromptAssembler(agentService, toolAssignments, List.of(), List.of(), lazy.isEnabled());
        AgentChatCaller chatCaller = new AgentChatCaller(clientRegistry, agentService, toolAssignments, recorder,
                new LlmRetry(), budgets, assembler, memoryStore, lazy, skillManager,
                knowledgeRetriever, timeouts);
        this.checkpointSaver = checkpointSaver;
        this.budgets = budgets != null ? budgets : new ContextBudgetProperties();
        this.orchestrationBudget = new OrchestrationBudget(this.budgets);
        this.nodes = new OrchestrationNodes(chatCaller, assembler, this.budgets, this.orchestrationBudget);
        this.streamPipeline = new MultiAgentStreamPipeline(
                (liveTokens, contentSent, toolEvents, cancelled, listener) ->
                        buildStateGraph(liveTokens, contentSent, toolEvents, cancelled)
                                .compile(compileConfig(listener)));
        try {
            this.stateGraph = buildStateGraph();
            // 同步执行用的常驻实例（带检查点时每个 superstep 自动落库）
            this.graph = stateGraph.compile(compileConfig(null));
        } catch (GraphStateException e) {
            throw new IllegalStateException("构建/编译多 Agent 编排 StateGraph 失败", e);
        }
    }

    /** 编译配置：挂检查点存储器（可 null）+ 生命周期监听器（可 null）；releaseThread=false 保留检查点供续跑 */
    private CompileConfig compileConfig(com.alibaba.cloud.ai.graph.GraphLifecycleListener listener) {
        CompileConfig.Builder builder = CompileConfig.builder().releaseThread(false);
        if (checkpointSaver != null) {
            builder.saverConfig(SaverConfig.builder().register(checkpointSaver).build());
        }
        if (listener != null) {
            builder.withLifecycleListener(listener);
        }
        return builder.build();
    }

    /**
     * 执行用 RunnableConfig：threadId=goalId（检查点归属键）+ 子任务并行扇出限并发
     * （subtask-concurrency > 0 时经 ParallelNode 的 metadata 信号量排队错峰；
     * metadata 键与并行节点 id 对应关系见 {@link ParallelNode#formatMaxConcurrencyKey}）。
     */
    private RunnableConfig runnableConfig(String goalId) {
        return runnableConfig(goalId, null);
    }

    /** 同上，可带检查点 ID（断点续跑从该检查点恢复） */
    private RunnableConfig runnableConfig(String goalId, String checkPointId) {
        RunnableConfig.Builder builder = RunnableConfig.builder().threadId(goalId);
        if (checkPointId != null) {
            builder.checkPointId(checkPointId);
        }
        int concurrency = budgets.getSubtaskConcurrency();
        if (concurrency > 0) {
            // 并行节点 id 为 formatNodeId(NODE_LEAD)（__PARALLEL__(lead)），与其 metadata 键配对；
            // ParallelNode 位于 graph-core internal 包但类型/工厂方法公开，键名随上游联动
            builder.addMetadata(
                    ParallelNode.formatMaxConcurrencyKey(ParallelNode.formatNodeId(NODE_LEAD)),
                    concurrency);
        }
        return builder.build();
    }

    @Override
    public String name() {
        return agentName;
    }

    /** 编排 input 组装：objective/sessionId + 轨迹标识（turn/trace 取 Goal，null 不放键）+ 预算账本 */
    private Map<String, Object> inputOf(Goal goal) {
        Map<String, Object> input = new HashMap<>();
        input.put(K_OBJECTIVE, goal.objective());
        input.put(K_SESSION_ID, goal.sessionId());
        if (goal.turnId() != null) {
            input.put(K_TURN_ID, goal.turnId());
        }
        if (goal.traceId() != null) {
            input.put(K_TRACE_ID, goal.traceId());
        }
        orchestrationBudget.putLedger(input);
        return input;
    }

    /** 执行复杂目标：把客观目标注入 StateGraph，返回最终回答。 */
    @Override
    public String execute(Goal goal) {
        log.info("[multi-agent] 开始编排复杂目标: {}", goal.objective());
        return graph.invoke(inputOf(goal), runnableConfig(goal.id()))
                .flatMap(s -> s.value(K_FINAL, String.class))
                .orElse(goal.objective());
    }

    /**
     * 流式执行复杂目标：stream 主干帧 + 双旁路（生命周期钩子 + 聚合 token）。
     *
     * <p>主干：{@link CompiledGraph#stream(Map)} 帧 → {@link MultiAgentStreamPipeline#toRows} 行；
     * 旁路与死锁教训详见 {@link MultiAgentStreamPipeline}。
     *
     * <p>子任务节点保持阻塞调用：多子任务并行执行，token 直推会交错乱序；用户体感关键
     * 在最终回答的打字机效果，由聚合节点承担。lead 产出为 JSON 中间产物，不推送 token。
     */
    @Override
    public Flux<String> executeStreamReactive(Goal goal) {
        return streamPipeline.run(inputOf(goal), goal.objective(), runnableConfig(goal.id()));
    }

    /**
     * 断点续跑：从该 goal 上次编排的检查点继续（threadId=goalId）。
     * 已完成节点（如 lead 拆解、已批量完成的子任务）不再重跑，只补执行缺口；
     * 聚合节点重新汇总（读检查点中已有的全量 result_*）。
     *
     * <p>校验在方法调用时同步完成（快速失败）：
     * 未启用检查点 / 无该 goal 的检查点记录时抛 {@link IllegalStateException}。
     *
     * <p>输出语义与 {@link #executeStreamReactive(Goal)} 完全一致（进度 + 打字机）。
     *
     * <p>graph-core 1.1.x 续跑触发条件是 config.checkPointId 非空
     * （GraphRunnerContext#initializeFromResume：state 自动合并 checkpoint 状态），
     * 而 saver.get() 对带 checkPointId 的 config 按 ID 精确匹配——
     * 因此先探测最新 checkpoint，再以其真实 ID 组装续跑 config。
     *
     * <p>恢复点选择（{@link MultiAgentStreamPipeline#selectResumeCheckpoint}）：
     * 编排已完成（final 有效）→ 从最终检查点零调用回放；
     * 否则回退到「子任务批完成、聚合前」的检查点（nextNodeId=aggregate）补跑聚合。
     */
    public Flux<String> resumeStreamReactive(Goal goal) {
        if (checkpointSaver == null) {
            throw new IllegalStateException("未启用检查点存储，无法续跑");
        }
        RunnableConfig probe = runnableConfig(goal.id());
        Checkpoint target;
        try {
            java.util.Collection<Checkpoint> checkpoints = checkpointSaver.list(probe);
            if (checkpoints.isEmpty()) {
                throw new IllegalStateException(
                        "无可续跑的编排：goal " + goal.id() + " 没有检查点（可能未走过复杂路径）");
            }
            target = MultiAgentStreamPipeline.selectResumeCheckpoint(checkpoints);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取检查点失败: " + goal.id(), e);
        }
        log.info("[multi-agent] 断点续跑 goal {}: 从检查点 {} 继续（nextNodeId={}，已完成节点不再重跑）",
                goal.id(), target.getId(), target.getNextNodeId());
        return streamPipeline.run(inputOf(goal), goal.objective(),
                runnableConfig(goal.id(), target.getId()));
    }

    /* ---------------- StateGraph 构建 ---------------- */

    /** 子任务节点名 */
    private static String subtaskName(int i) {
        return SUBTASK_NODE_PREFIX + i;
    }

    private StateGraph buildStateGraph() throws GraphStateException {
        return buildStateGraph(null, null, null, null);
    }

    /**
     * 构建「lead → 并行子任务 → 聚合」拓扑。
     *
     * @param liveTokens  非 null 时聚合节点走流式调用并把 token 旁路发射到该 sink（含首个 token 前的「聚合」进度行）；
     *                    null 时聚合节点阻塞调用（同步 execute 路径）
     * @param contentSent 流式模式的内容已发射标志（与主干 {@link MultiAgentStreamPipeline#toRows} 共享，防重复发射）；可为 null
     * @param toolEvents  非 null 时子任务节点注入追踪版工具（执行起止经该 sink 发进度行，供 CLI 工具调用行）
     * @param cancelled   非 null 时节点执行前检查该标志：客户端已断开则短路（不再发起新的 LLM 调用）
     */
    private StateGraph buildStateGraph(Sinks.Many<String> liveTokens,
                                       AtomicBoolean contentSent,
                                       Sinks.Many<String> toolEvents,
                                       AtomicBoolean cancelled) throws GraphStateException {
        // 注册编排 state 键的覆盖合并策略。关键：graph-core resume 时以 OverAllState#input()
        // 合并 checkpoint 状态，只保留「已注册 KeyStrategy」的键；不注册则断点续跑时
        // subtask/result/final 等全部丢失（全新执行走 withData 无此过滤，故首跑不受影响）
        StateGraph g = new StateGraph(MultiAgentGraphAgent::stateKeyStrategies);
        // 子任务工具事件发射器：并行节点可能同时回调，经 Sink 锁串行化
        java.util.function.Consumer<String> toolEmitter = toolEvents == null ? null
                : row -> BranchProgressListener.tryEmitSerialized(toolEvents, row);

        // lead：拆解复杂目标为多条子任务
        g.addNode(NODE_LEAD, AsyncNodeAction.node_async(state -> nodes.lead(state, cancelled)));
        // 子任务池：固定 MAX_SUBTASKS 个并行节点
        for (int i = 0; i < MAX_SUBTASKS; i++) {
            final int idx = i;
            g.addNode(subtaskName(idx),
                    AsyncNodeAction.node_async(state -> nodes.subtask(state, idx, toolEmitter, cancelled)));
        }
        // 聚合：收集各子任务结果生成最终回答（流式模式逐 token 旁路推送）
        g.addNode(NODE_AGGREGATE,
                AsyncNodeAction.node_async(state -> nodes.aggregate(state, liveTokens, contentSent, cancelled)));

        // 并联：lead → 同时派发到所有子任务节点（addEdge(from, List) 并行扇出）
        List<String> subtasks = new ArrayList<>();
        for (int i = 0; i < MAX_SUBTASKS; i++) {
            subtasks.add(subtaskName(i));
        }
        g.addEdge(NODE_LEAD, subtasks);
        // 各子任务 → 聚合
        for (int i = 0; i < MAX_SUBTASKS; i++) {
            g.addEdge(subtaskName(i), NODE_AGGREGATE);
        }
        // 聚合 → 结束
        g.addEdge(NODE_AGGREGATE, StateGraph.END);
        // 入口：START → lead
        g.addEdge(StateGraph.START, NODE_LEAD);

        return g;
    }

    /**
     * 编排 state 全部键的注册策略（覆盖语义，与节点直接 put 的现有行为一致）：
     * 输入键 + 拆解产物 + 各子任务槽位 + 最终回答。
     */
    private static Map<String, KeyStrategy> stateKeyStrategies() {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        KeyStrategy replace = new ReplaceStrategy();
        strategies.put(K_OBJECTIVE, replace);
        strategies.put(K_SESSION_ID, replace);
        // 轨迹键（Replace：续跑时同 goal 的 turn/trace 覆盖值不变；leadSpan 续跑时 input 不含该键，
        // 沿用检查点中 lead 真实调用落下的 span——派生调用的 parent_span 始终指向真实 lead span）
        strategies.put(K_TURN_ID, replace);
        strategies.put(K_TRACE_ID, replace);
        strategies.put(K_LEAD_SPAN, replace);
        strategies.put(K_SUBTASK_COUNT, replace);
        strategies.put(K_FINAL, replace);
        // 编排预算账本（可空：budget=0 时不注入；Replace 策略保证续跑时新账本覆盖旧值）
        strategies.put(OrchestrationBudget.K_TOKEN_LEDGER, replace);
        strategies.put(OrchestrationBudget.K_TOKEN_ESTIMATED, replace);
        for (int i = 0; i < MAX_SUBTASKS; i++) {
            strategies.put(K_SUBTASK_PREFIX + i, replace);
            strategies.put(K_SUBTASK_AGENT_PREFIX + i, replace);
            strategies.put(K_RESULT_PREFIX + i, replace);
        }
        return strategies;
    }

}
