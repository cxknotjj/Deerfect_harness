package com.dark.javaHarness.agent.orchestrate;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dark.javaHarness.advisor.PromptBudgetAdvisor;
import com.dark.javaHarness.agent.AgentChatCaller;
import com.dark.javaHarness.agent.AggregateStreamGuard;
import com.dark.javaHarness.agent.CallTrace;
import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.service.impl.route.RagPrefetcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

/**
 * 编排三节点实现（自 {@link MultiAgentGraphAgent} 拆出，超长类拆分 2026-09-25）：
 * lead 拆解 / subtask 子任务执行 / aggregate 聚合，及节点→caller 的 predict* 桥接与
 * 流式聚合护栏 {@link AggregateStreamGuard} 的所有权。
 *
 * <p>状态键常量仍归宿主（是 {@link MultiAgentStreamPipeline} 的跨类契约），本类经
 * {@code MultiAgentGraphAgent.K_*} 引用；图拓扑装配与执行/续跑入口留在宿主，
 * 节点方法签名逐字不变（宿主图装配 lambda 直接委托本类）。
 */
final class OrchestrationNodes {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationNodes.class);

    /** 编排环节角色名（agent 表行名）：lead 拆解器、aggregator 聚合器，与编排器 multi-agent 行解耦 */
    private static final String ROLE_LEAD = "lead";
    private static final String ROLE_AGGREGATOR = "aggregator";

    private final AgentChatCaller chatCaller;
    /** Prompt 组装器：子任务专家 persona 与各环节 system 段统一经此组装 */
    private final PromptAssembler promptAssembler;
    private final ContextBudgetProperties budgets;
    private final OrchestrationBudget orchestrationBudget;
    /** 流式聚合护栏（失败自愈策略见 AggregateStreamGuard；聚合必发不受熔断） */
    private final AggregateStreamGuard streamGuard;
    /** 子任务入口 RAG 预取器（与知识库同条件装配，可 null=知识库禁用 → 预取钩子零行为） */
    private final RagPrefetcher ragPrefetcher;
    /**
     * 工具包定义读取器（agent 表 is_internal=0 且 tools 列非空的包行，包工具并入请求工具面 +
     * 包纪律段拼入 user 文本）；可 null=包解析零行为（单测场景，对齐 ragPrefetcher 先例）。
     */
    private final com.dark.javaHarness.service.AgentConfigProvider agentConfigProvider;

    OrchestrationNodes(AgentChatCaller chatCaller,
                       PromptAssembler promptAssembler,
                       ContextBudgetProperties budgets,
                       OrchestrationBudget orchestrationBudget) {
        this(chatCaller, promptAssembler, budgets, orchestrationBudget, null, null);
    }

    OrchestrationNodes(AgentChatCaller chatCaller,
                       PromptAssembler promptAssembler,
                       ContextBudgetProperties budgets,
                       OrchestrationBudget orchestrationBudget,
                       RagPrefetcher ragPrefetcher) {
        this(chatCaller, promptAssembler, budgets, orchestrationBudget, ragPrefetcher, null);
    }

    OrchestrationNodes(AgentChatCaller chatCaller,
                       PromptAssembler promptAssembler,
                       ContextBudgetProperties budgets,
                       OrchestrationBudget orchestrationBudget,
                       RagPrefetcher ragPrefetcher,
                       com.dark.javaHarness.service.AgentConfigProvider agentConfigProvider) {
        this.chatCaller = chatCaller;
        this.promptAssembler = promptAssembler;
        this.budgets = budgets;
        this.orchestrationBudget = orchestrationBudget;
        this.ragPrefetcher = ragPrefetcher;
        this.agentConfigProvider = agentConfigProvider;
        this.streamGuard = new AggregateStreamGuard(this.chatCaller, ROLE_AGGREGATOR,
                OrchestrationPrompts.AGGREGATOR_FALLBACK_PROMPT, this::aggregateBudgetAdvisor);
    }

    /* ------------ 节点实现（同步 NodeAction，返回状态更新 Map） ------------ */

    /** 客户端已断开则跳过本节点的 LLM 调用（同步路径 cancelled 为 null，恒不短路） */
    private static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    /**
     * lead：把 objective 拆解为 N 条子任务（可带专家指派），
     * 写 subtask_0..n-1、subtaskAgent_0..n-1、subtaskBrief_0..n-1 与 subtaskCount。
     * 消耗经门控账本句柄按 roundtrip 增量记账（lead 是编排首调用，发起前账本为 0 不会熔断；
     * 极小预算下 usage 帧中途熔断时降级为「拆解失败」——退化为单子任务，交由后续熔断跳过，
     * 聚合注入降级说明）。lead 自身消耗计入账本供后续判定。
     */
    Map<String, Object> lead(OverAllState state, AtomicBoolean cancelled, Consumer<String> leadEmitter) {
        if (isCancelled(cancelled)) {
            log.info("[multi-agent][lead] 客户端已断开，跳过拆解");
            return new HashMap<>();
        }
        String objective = state.value(MultiAgentGraphAgent.K_OBJECTIVE, String.class).orElse("");
        String sessionId = state.value(MultiAgentGraphAgent.K_SESSION_ID, String.class).orElse(null);
        // 轨迹贯通：lead 为编排根调用（parentSpan=null），spanId 在发起前生成并写入状态键——
        // subtask/aggregate 节点读出作各自 parent_span（续跑时沿检查点沿用，不重生成）
        String turnId = state.value(MultiAgentGraphAgent.K_TURN_ID, String.class).orElse(null);
        String traceId = state.value(MultiAgentGraphAgent.K_TRACE_ID, String.class).orElse(null);
        String leadSpanId = CallTrace.newSpanId();
        CallTrace leadTrace = new CallTrace(turnId, traceId, null, leadSpanId);
        // 思考透传（agent 表 thinking 列显示口径）：lead 行 thinking=1 且流式旁路可用时，
        // reasoningContent delta 编码为「思考 · lead」进度行经旁路 sink 合入（不节流，与聚合同口径）
        Consumer<String> reasoningTap = null;
        if (leadEmitter != null && thinkingDisplayOf(ROLE_LEAD)) {
            reasoningTap = delta -> leadEmitter.accept(ProgressLine.encode("思考 · lead", delta));
        }
        String content;
        try {
            content = predictLeadLogged(sessionId, objective, cancelled,
                    orchestrationBudget.ledgerHandle(state, true), leadTrace, reasoningTap);
        } catch (BudgetLedger.BudgetExceededException e) {
            log.warn("[multi-agent][lead] 编排预算超限中止拆解（已消耗 {} / 上限 {}），退化为单子任务",
                    OrchestrationBudget.ledgerValue(state), budgets.getOrchestrationBudget());
            content = null; // 拆解产物缺失 → 退化为单个子任务=objective，执行前被熔断跳过
        }
        List<LeadOutputParser.Subtask> items = LeadOutputParser.parseSubtasks(content);
        if (items.isEmpty()) {
            // 拆解失败：退化为单个子任务=objective
            items.add(new LeadOutputParser.Subtask(objective, null, null, List.of()));
        }
        Map<String, Object> updates = new HashMap<>();
        int n = Math.min(items.size(), resolvedMaxSubtasks());
        updates.put(MultiAgentGraphAgent.K_LEAD_SPAN, leadSpanId);
        updates.put(MultiAgentGraphAgent.K_SUBTASK_COUNT, n);
        for (int i = 0; i < n; i++) {
            LeadOutputParser.Subtask item = items.get(i);
            updates.put(MultiAgentGraphAgent.K_SUBTASK_PREFIX + i, item.desc());
            updates.put(MultiAgentGraphAgent.K_SUBTASK_AGENT_PREFIX + i, item.agent());
            // 任务书随槽位落状态，截断在 lead 出口完成（写入即定稿）；旧 checkpoint 缺键 orElse(null) 安全。
            // brief 长文本不参与 RAG 预取（下方预取 query 仍为 desc），只作子任务执行的 user 文本
            updates.put(MultiAgentGraphAgent.K_SUBTASK_BRIEF_PREFIX + i, truncateBrief(item.brief()));
            // 包名 CSV 随槽位落状态：空名单写空串（读侧 orElse("") 归一化，两种口径同义=无包）
            updates.put(MultiAgentGraphAgent.K_SUBTASK_PACKS_PREFIX + i, String.join(",", item.packs()));
        }
        log.info("[multi-agent][lead] 拆解为 {} 个子任务，指派：{}", n,
                items.subList(0, n).stream().map(LeadOutputParser.Subtask::agent).toList());
        // 子任务 RAG 预取主提交点（lead 出口，spec 3.6）：拆解产物落定后立即对全部已布置槽位
        // 批量提交，把检索延迟藏进子任务批的排队/执行等待窗口（subtask-concurrency 信号量在
        // 图驱动层先取许可再执行节点方法体，节点入口钩子覆盖不到排队空档——主提交点随 spec
        // 修订上移至此）。agentName 与执行期完全同源（resolvedExpert 口径），query=子任务文本；
        // fire-and-forget：prefetcher 缺位（知识库禁用）或异常均静默，不影响拆解主流程；
        // 与 subtask 入口补提交同参数重复幂等（KnowledgeRetriever 复合缓存键 sessionId+query），无需去重
        if (ragPrefetcher != null) {
            for (int i = 0; i < n; i++) {
                LeadOutputParser.Subtask item = items.get(i);
                try {
                    ragPrefetcher.submit(resolvedExpert(item.agent()), sessionId, item.desc(),
                            KnowledgeRetriever.SOURCE_LEAD_PREFETCH);
                } catch (Exception e) {
                    log.debug("[multi-agent][lead] RAG 预取提交失败（静默）：{}", e.getMessage());
                }
            }
        }
        return updates;
    }

    /**
     * 任务书截断：超上限（app.context.subtask-brief-max-chars，0=不限制）时前 N 字符截断，
     * warn 单行含前后长度。brief 长文本不参与 RAG 预取（预取 query=desc 口径不变），只作子任务 user 文本。
     */
    private String truncateBrief(String brief) {
        if (brief == null) {
            return null;
        }
        int max = budgets.getSubtaskBriefMaxChars();
        if (max <= 0 || brief.length() <= max) {
            return brief;
        }
        log.warn("[multi-agent][lead] 任务书超限截断：{} -> {} 字符", brief.length(), max);
        return brief.substring(0, max);
    }

    /**
     * 拆解数量上限（app.context.subtask-max-count）：clamp [1, 兜底4]，0/超界回退兜底
     * （拆解数量无"不限制"安全语义）。仅用于截断 lead 拆解产物；KeyStrategy 槽位 0..3
     * 仍由 {@link MultiAgentGraphAgent#MAX_SUBTASKS} 界定，两者解耦。
     */
    private int resolvedMaxSubtasks() {
        int configured = budgets.getSubtaskMaxCount();
        if (configured < 1 || configured > MultiAgentGraphAgent.MAX_SUBTASKS) {
            return MultiAgentGraphAgent.MAX_SUBTASKS;
        }
        return configured;
    }

    /**
     * 子任务节点：读 subtask_i、指派的 subtaskAgent_i 与任务书 subtaskBrief_i，若存在则调用对应专家
     * ChatClient 生成 result_i。熔断下沉到 caller（方向 b）：门控账本句柄在调用发起前（零 HTTP）与每轮 roundtrip 的
     * usage 帧上检查，超限抛 {@link BudgetLedger.BudgetExceededException}——节点捕获后
     * result 写「预算超限跳过」占位，聚合据此注入降级说明。并行扇出下共享账本让后序调用
     * 及时看到前序消耗，配合 subtask-concurrency 错峰使「部分跳过」成为常态而非偶发。
     */
    Map<String, Object> subtask(OverAllState state, int idx,
                                java.util.function.Consumer<String> toolEmitter,
                                AtomicBoolean cancelled) {
        String task = state.value(MultiAgentGraphAgent.K_SUBTASK_PREFIX + idx, String.class).orElse(null);
        if (task == null || task.isBlank()) {
            return new HashMap<>(); // lead 未设置该子任务 → 快速短路
        }
        if (isCancelled(cancelled)) {
            log.info("[multi-agent][subtask-{}] 客户端已断开，跳过专家调用", idx);
            return new HashMap<>();
        }
        String expert = state.value(MultiAgentGraphAgent.K_SUBTASK_AGENT_PREFIX + idx, String.class).orElse(null);
        // 任务书读出：旧 checkpoint 缺键 → null（执行退化为 task/desc，口径见 predictSubtask）
        String brief = state.value(MultiAgentGraphAgent.K_SUBTASK_BRIEF_PREFIX + idx, String.class).orElse(null);
        // 工具包读出：旧 checkpoint 缺键 → orElse("") 归一化为无包（与空名单同口径，执行退化现状）
        PackResolution packs = resolvePacks(
                state.value(MultiAgentGraphAgent.K_SUBTASK_PACKS_PREFIX + idx, String.class).orElse(""));
        String sessionId = state.value(MultiAgentGraphAgent.K_SESSION_ID, String.class).orElse(null);
        // 子任务入口 RAG 预取补提交（fire-and-forget）：主提交点已上移 lead 出口（排队空档消化
        // 检索延迟，spec 3.6），本钩子保留覆盖断点续跑（lead 不重跑、直接进入 subtask 节点）场景；
        // 与 lead 出口首跑提交同参数重复幂等（KnowledgeRetriever 复合缓存键），无需去重。
        // agentName 与执行期完全同源（同一 resolvedExpert 口径），query=子任务文本；
        // 预取是纯加速：prefetcher 缺位（知识库禁用）或任何异常均静默，返回值忽略，
        // 不等待完成、不影响编排主流程
        if (ragPrefetcher != null) {
            try {
                ragPrefetcher.submit(resolvedExpert(expert), sessionId, task,
                        KnowledgeRetriever.SOURCE_SUBTASK_PREFETCH);
            } catch (Exception e) {
                log.debug("[multi-agent][subtask-{}] RAG 预取提交失败（静默）：{}", idx, e.getMessage());
            }
        }
        // 轨迹贯通：派生调用挂同一执行链（turn/trace），parent_span=lead 的 span_id
        CallTrace trace = new CallTrace(state.value(MultiAgentGraphAgent.K_TURN_ID, String.class).orElse(null),
                state.value(MultiAgentGraphAgent.K_TRACE_ID, String.class).orElse(null),
                state.value(MultiAgentGraphAgent.K_LEAD_SPAN, String.class).orElse(null), null);
        // 子任务墙钟超时（app.context.subtask-wall-clock-seconds，0 = 不限制）：把断连令牌包装为
        // 「断连 OR 超时」供给——独立包装、绝不置位共享 AtomicBoolean（超时不是客户端断开，
        // 置位会连坐短路同批并行子任务与聚合）；超时命中时 caller 在 token 边界抛
        // CancellationException，节点捕获后与真实断连区分（真实断连原样上抛保持现状）
        int wallClock = budgets.getSubtaskWallClockSeconds();
        long deadlineNanos = wallClock > 0 ? System.nanoTime() + wallClock * 1_000_000_000L : 0L;
        java.util.function.BooleanSupplier cancelSignal =
                (cancelled == null && deadlineNanos == 0) ? null
                        : deadlineNanos == 0 ? cancelled::get
                        : cancelled == null ? () -> System.nanoTime() >= deadlineNanos
                        : () -> cancelled.get() || System.nanoTime() >= deadlineNanos;
        long startNanos = System.nanoTime();
        // 思考透传（agent 表 thinking 列显示口径：仅控制是否显示思考内容，并不是控制模型是否思考）：
        // expert 行 thinking=1 且流式旁路可用时，经合帧节流器透出「思考N · 专家名」进度行
        // （N=idx+1 与前端「第 N 个子任务」口径一致；stage 携带归属专家，消除归属歧义）；
        // 同步路径 toolEmitter=null 无显示通道不透传
        ThinkingThrottle thinkingThrottle = null;
        Consumer<String> reasoningTap = null;
        if (toolEmitter != null && thinkingDisplayOf(resolvedExpert(expert))) {
            thinkingThrottle = new ThinkingThrottle(
                    "思考" + (idx + 1) + " · " + resolvedExpert(expert), toolEmitter);
            reasoningTap = thinkingThrottle;
        }
        String result;
        try {
            result = predictSubtask(sessionId, task, brief, expert, toolEmitter, cancelSignal,
                    orchestrationBudget.ledgerHandle(state, true), trace, packs, reasoningTap);
            if (thinkingThrottle != null) {
                // 流正常结束兜底发射残余缓冲（异常中止场景不执行此行，缓冲随作用域丢弃）
                thinkingThrottle.flush();
            }
        } catch (BudgetLedger.BudgetExceededException e) {
            log.warn("[multi-agent][subtask-{}] 编排 token 消费已达上限（{} / {}），跳过专家调用",
                    idx, OrchestrationBudget.ledgerValue(state), budgets.getOrchestrationBudget());
            Map<String, Object> updates = new HashMap<>();
            updates.put(MultiAgentGraphAgent.K_RESULT_PREFIX + idx, MultiAgentGraphAgent.SKIPPED_RESULT);
            return updates;
        } catch (java.util.concurrent.CancellationException e) {
            // 超时命中判定：deadline 已过且共享断连令牌未置位——真实客户端断开（令牌置位）
            // 原样上抛（图终止、不写占位，现状不变）；超时写占位结果，聚合注入超时说明
            if (deadlineNanos == 0 || System.nanoTime() < deadlineNanos
                    || (cancelled != null && cancelled.get())) {
                throw e;
            }
            log.warn("[multi-agent][subtask-{}] 子任务执行超时中止（耗时 {}s / 上限 {}s），写占位结果",
                    idx, (System.nanoTime() - startNanos) / 1_000_000_000L, wallClock);
            Map<String, Object> updates = new HashMap<>();
            updates.put(MultiAgentGraphAgent.K_RESULT_PREFIX + idx, MultiAgentGraphAgent.TIMEOUT_SKIPPED_RESULT);
            return updates;
        } catch (Exception e) {
            // 其余执行失败（模型调用异常/重试耗尽/看门狗 120s 零帧中止等）：写占位结果不向图上抛——
            // 并行节点任一子任务异常会让整图失败、触发入口层降级 general 重答，其他子任务的
            // 真实结果全部作废；失败占位后其余子任务照常完成，聚合注入失败说明（2026-10-02）
            log.warn("[multi-agent][subtask-{}] 子任务执行失败，写占位结果：{}", idx, safeMessage(e), e);
            Map<String, Object> updates = new HashMap<>();
            updates.put(MultiAgentGraphAgent.K_RESULT_PREFIX + idx, MultiAgentGraphAgent.FAILED_RESULT);
            return updates;
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(MultiAgentGraphAgent.K_RESULT_PREFIX + idx, result);
        log.info("[multi-agent][subtask-{}] 完成（专家={}），结果长度={}", idx, expert, result.length());
        return updates;
    }

    /**
     * 聚合节点实现：非流式（liveTokens=null）阻塞调用；流式时逐 token 旁路发射，
     * 首个内容 token 前先发「聚合」进度行，失败回退阻塞调用（未推过 token 时主干兜底发完整内容）。
     * cancelled 非 null 且已置位时短路：不再调 LLM，占位收尾。
     *
     * <p>预算降级（熔断后聚合必发）：被熔断子任务的占位 result 不作为真实结果喂给模型，
     * 改为在 prompt 前置降级说明（N 个子任务因预算超限未执行 + 已消耗/上限数字 + 估算口径），
     * 聚合自身不受预算熔断（照常执行，消耗照常上报账本）。
     */
    Map<String, Object> aggregate(OverAllState state,
                                  Sinks.Many<String> liveTokens,
                                  AtomicBoolean contentSent,
                                  AtomicBoolean cancelled) {
        if (isCancelled(cancelled)) {
            // 短路不写占位 final：避免「假完成」状态落检查点，导致续跑无法补跑聚合
            log.info("[multi-agent][aggregate] 客户端已断开，跳过聚合调用");
            return new HashMap<>();
        }
        int n = state.value(MultiAgentGraphAgent.K_SUBTASK_COUNT, Integer.class).orElse(0);
        String sessionId = state.value(MultiAgentGraphAgent.K_SESSION_ID, String.class).orElse(null);
        // 轨迹贯通：聚合为派生调用（parent_span=lead 的 span_id），与子任务同树
        CallTrace trace = new CallTrace(state.value(MultiAgentGraphAgent.K_TURN_ID, String.class).orElse(null),
                state.value(MultiAgentGraphAgent.K_TRACE_ID, String.class).orElse(null),
                state.value(MultiAgentGraphAgent.K_LEAD_SPAN, String.class).orElse(null), null);
        List<String> results = new ArrayList<>();
        int skipped = 0;
        int timedOut = 0;
        int failed = 0;
        for (int i = 0; i < n; i++) {
            String r = state.value(MultiAgentGraphAgent.K_RESULT_PREFIX + i, String.class).orElse(null);
            if (r == null || r.isBlank()) {
                continue; // 子任务失败（异常上抛的旧路径/短路槽位）无结果
            }
            if (MultiAgentGraphAgent.SKIPPED_RESULT.equals(r)) {
                skipped++; // 预算熔断跳过：不计入真实结果，降级说明交代
                continue;
            }
            if (MultiAgentGraphAgent.TIMEOUT_SKIPPED_RESULT.equals(r)) {
                timedOut++; // 墙钟超时跳过：不计入真实结果，超时说明交代（与预算降级互不掺混）
                continue;
            }
            if (MultiAgentGraphAgent.FAILED_RESULT.equals(r)) {
                failed++; // 执行失败跳过：不计入真实结果，失败说明交代（与预算/超时互不掺混）
                continue;
            }
            results.add(r);
        }
        String finalAnswer;
        if (results.isEmpty() && skipped == 0 && timedOut == 0 && failed == 0) {
            // 子任务全失败（旧路径异常上抛，无任何占位）：兜底（原有行为）
            finalAnswer = state.value(MultiAgentGraphAgent.K_FINAL, String.class).orElse("（未生成最终回答）");
        } else {
            // 有真实结果或存在占位（含全部被跳过/超时/失败）：照常调聚合模型（聚合必发，
            // 与预算/超时占位同语义），各说明段前置交代占比
            String user = OrchestrationPrompts.aggregateUserPrompt(results);
            if (skipped > 0) {
                user = orchestrationBudget.degradationNote(skipped, state) + "\n\n" + user;
            }
            if (timedOut > 0) {
                // 超时说明（与预算降级说明同构的前置段；degradationNote 文案为预算专属，超时独立成段）
                user = timeoutNote(timedOut) + "\n\n" + user;
            }
            if (failed > 0) {
                // 失败说明（与预算/超时说明同构的前置段，互不掺混）
                user = failedNote(failed) + "\n\n" + user;
            }
            if (liveTokens == null) {
                // 同步路径 cancelled 为 null（无取消语义），流式路径传共享断连标志
                finalAnswer = predictAggregate(sessionId, user, cancelled,
                        orchestrationBudget.ledgerHandle(state, false), trace);
            } else {
                // 思考透传（agent 表 thinking 列显示口径）：aggregator 行 thinking=1 时经 liveTokens
                // 旁路以「思考 · 聚合」进度行发射（进度行不置位 contentSent，不影响 END 兜底）
                Consumer<String> aggregateTap = null;
                if (thinkingDisplayOf(ROLE_AGGREGATOR)) {
                    aggregateTap = delta -> BranchProgressListener.tryEmitSerialized(liveTokens,
                            ProgressLine.encode("思考 · 聚合", delta));
                }
                finalAnswer = streamGuard.predictStreaming(sessionId, user, liveTokens, contentSent,
                        cancelled, orchestrationBudget.ledgerHandle(state, false), trace, aggregateTap);
            }
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(MultiAgentGraphAgent.K_FINAL, finalAnswer);
        log.info("[multi-agent][aggregate] 汇总 {} 个子任务结果（预算超限跳过 {} 个，超时跳过 {} 个，失败跳过 {} 个）",
                results.size(), skipped, timedOut, failed);
        return updates;
    }

    /** 超时说明（聚合 prompt 前置）：N 个子任务执行超时未完成，与预算降级说明同构、互不掺混 */
    private static String timeoutNote(int timedOut) {
        return "【超时说明】" + timedOut + " 个子任务执行超时未完成，以下子任务结果不完整。"
                + "请基于已有内容汇总最终回答，并在回答开头简要说明部分内容因超时未覆盖。";
    }

    /** 失败说明（聚合 prompt 前置）：N 个子任务执行失败已跳过，与预算/超时说明同构、互不掺混 */
    private static String failedNote(int failed) {
        return "【失败说明】" + failed + " 个子任务执行失败已跳过，以下子任务结果不包含其内容。"
                + "请基于已有内容汇总最终回答，并在回答开头简要说明部分内容因子任务失败未覆盖。";
    }

    /** 异常消息摘要（null 兜底类名，日志/占位说明用） */
    private static String safeMessage(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /* ---------- ChatClient 单次调用 ---------- */

    /**
     * lead 拆解：按 agent 表 lead 行的提示词/模型执行（无配置时回退内置兜底）；目标超长尾截至 lead 预算。
     * cancelled 传节点共享断连标志（同步路径为 null）：调用前已置位直接抛取消异常（零 HTTP 请求），
     * 执行中置位在下一个 token 边界中止在途请求。budgetLedger 门控账本句柄（caller 发起前与
     * 每轮 roundtrip 熔断判定 + 增量记账；可 null）。
     */
    private String predictLead(String sessionId, String objective, AtomicBoolean cancelled,
                               BudgetLedger budgetLedger, CallTrace trace, Consumer<String> reasoningTap) {
        return chatCaller.call(sessionId, ROLE_LEAD, OrchestrationPrompts.LEAD_FALLBACK_PROMPT, "拆解目标：" + objective,
                null, new PromptBudgetAdvisor[]{PromptBudgetAdvisor.tail(budgets.getLeadBudget())},
                cancelled == null ? null : cancelled::get, budgetLedger, trace, null, reasoningTap);
    }

    /** lead 拆解前日志埋点便于诊断专家指派（raw 输出统一记审计） */
    private String predictLeadLogged(String sessionId, String objective, AtomicBoolean cancelled,
                                     BudgetLedger budgetLedger, CallTrace trace, Consumer<String> reasoningTap) {
        String raw = predictLead(sessionId, objective, cancelled, budgetLedger, trace, reasoningTap);
        log.info("[multi-agent][lead] raw 拆解输出: {}", raw.length() > 300 ? raw.substring(0, 300) + "..." : raw);
        return raw;
    }

    /** 专家名同源解析：未指派（null/空白）回退默认专家 general——执行期与预取钩子共用同一口径 */
    private static String resolvedExpert(String expert) {
        return (expert == null || expert.isBlank()) ? AgentConstants.DEFAULT_AGENT : expert;
    }

    /**
     * agent 表 thinking 显示口径判定（仅控制是否显示思考内容，并不是控制模型是否思考——
     * 模型思考由 model_provider.disable_thinking 端点配置决定）：行 thinking=1 才透传；
     * 行缺失/查询失败/列 NULL 或 0 均不透传（getAgentConfig 内部容错返回 empty，此处不抛）。
     * provider null（单测场景）恒不透传。
     */
    private boolean thinkingDisplayOf(String agentName) {
        if (agentConfigProvider == null || agentName == null || agentName.isBlank()) {
            return false;
        }
        return agentConfigProvider.getAgentConfig(agentName)
                .map(cfg -> Boolean.TRUE.equals(cfg.thinking()))
                .orElse(false);
    }

    /**
     * 子任务执行：按指派专家查配置调用。cancelSignal 为节点预包装的取消供给
     * （「客户端断连 OR 墙钟超时」组合语义，可 null=同步路径无取消也不限时——
     * 执行中供给返回 true 时在途调用随令牌中止，不再烧完剩余 token）。
     * budgetLedger 门控账本句柄：caller 发起前与每轮 roundtrip 熔断判定 +
     * 按 roundtrip 增量记账（可 null）。
     * user 文本口径：任务书 brief 非空 ? brief : task——brief 为自包含任务书（目标/背景/
     * 约束/交付物）时优先，旧格式/未提供时退化为 desc（task），对旧产物完全兼容；
     * packs 非空时包纪律段以 {@code \n\n【领域规范·包名】} 标题拼在 user 文本之后
     * （多包按声明顺序），包工具名单经 caller 新重载并入请求工具面。
     */
    private String predictSubtask(String sessionId, String task, String brief, String expert,
                                  java.util.function.Consumer<String> toolEmitter,
                                  java.util.function.BooleanSupplier cancelSignal,
                                  BudgetLedger budgetLedger, CallTrace trace,
                                  PackResolution packs, Consumer<String> reasoningTap) {
        // 未指派（lead 输出旧格式或漏 agent 字段）→ 回退 general：通用兜底且持有全量工具
        String resolved = resolvedExpert(expert);
        // 专家 persona 与工具使用纪律经 PromptAssembler 统一组装（原硬编码拼接已删除）：
        // persona 作角色段兜底传入，工具索引/工具纪律/输出约定等段由调用器组装时追加
        String persona = promptAssembler.subtaskPersona(resolved);
        String user = (brief != null && !brief.isBlank()) ? brief : task;
        // 包纪律段拼在 user 文本之后（brief/task 口径不变，无包时 null 原样——与第一阶段完全一致）
        if (packs != null && packs.disciplineText() != null) {
            user = user + packs.disciplineText();
        }
        return chatCaller.call(sessionId, resolved, persona, user, toolEmitter,
                new PromptBudgetAdvisor[0], cancelSignal, budgetLedger, trace,
                packs == null ? null : packs.extraToolNames(), reasoningTap);
    }

    /**
     * 子任务工具包解析（全程静默容错）：逐包查 agent 表包定义（is_internal=0 且 tools 列非空的行），
     * 包工具名单跨包去重保序收集（extraToolNames），包纪律段按声明顺序拼为
     * {@code \n\n【领域规范·包名】\n + prompt 原文}。CSV 空（无包声明/旧 checkpoint 缺键）
     * 或 provider 为 null（单测场景）→ 零解析返回空产物；包无效（无该行/is_internal=1/
     * tools 空白/查询异常）→ warn 单行跳过该包，不阻断子任务执行。
     */
    private PackResolution resolvePacks(String packsCsv) {
        if (packsCsv == null || packsCsv.isBlank() || agentConfigProvider == null) {
            return PackResolution.NONE;
        }
        List<String> toolNames = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        StringBuilder discipline = new StringBuilder();
        for (String raw : packsCsv.split(",")) {
            String packName = raw.trim();
            if (packName.isEmpty()) {
                continue;
            }
            com.dark.javaHarness.service.AgentConfigProvider.ToolPackDef pack =
                    agentConfigProvider.findToolPack(packName).orElse(null);
            if (pack == null) {
                log.warn("[multi-agent][subtask] 工具包 '{}' 无有效定义（is_internal=0 且 tools 列非空的包行），跳过",
                        packName);
                continue;
            }
            for (String tool : pack.toolNames()) {
                if (seen.add(tool)) {
                    toolNames.add(tool);
                }
            }
            if (pack.disciplinePrompt() != null && !pack.disciplinePrompt().isBlank()) {
                discipline.append("\n\n【领域规范·").append(packName).append("】\n")
                        .append(pack.disciplinePrompt());
            }
        }
        return new PackResolution(List.copyOf(toolNames),
                discipline.length() > 0 ? discipline.toString() : null);
    }

    /**
     * 工具包解析产物：extraToolNames 为并入请求工具面的工具名单（跨包去重保序，无包为空列表），
     * disciplineText 为拼入 user 文本的纪律段文本（无包/包无 prompt 为 null）。
     */
    private record PackResolution(List<String> extraToolNames, String disciplineText) {

        static final PackResolution NONE = new PackResolution(List.of(), null);
    }

    /**
     * 聚合阻塞语义调用，仅服务同步编排路径（execute，liveTokens=null）；
     * 流式路径的失败自愈已改为带护栏的流式重试（见 {@link AggregateStreamGuard}），不再经此兜底。
     * budgetLedger 为 record-only 句柄（聚合不受熔断，仅记账；可 null）。
     */
    private String predictAggregate(String sessionId, String user, AtomicBoolean cancelled,
                                    BudgetLedger budgetLedger, CallTrace trace) {
        return chatCaller.call(sessionId, ROLE_AGGREGATOR, OrchestrationPrompts.AGGREGATOR_FALLBACK_PROMPT, user,
                null, new PromptBudgetAdvisor[]{aggregateBudgetAdvisor()},
                cancelled == null ? null : cancelled::get, budgetLedger, trace);
    }

    /** 聚合预算 advisor：按「【子任务N】」节边界等份额截断（禁止先到先得挤掉后面的子任务） */
    private PromptBudgetAdvisor aggregateBudgetAdvisor() {
        return PromptBudgetAdvisor.sections(budgets.getAggregateBudget(), OrchestrationPrompts.AGG_SECTION_HEADER);
    }
}
