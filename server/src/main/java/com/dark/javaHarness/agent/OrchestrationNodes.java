package com.dark.javaHarness.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dark.javaHarness.advisor.PromptBudgetAdvisor;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.prompt.PromptAssembler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

    OrchestrationNodes(AgentChatCaller chatCaller,
                       PromptAssembler promptAssembler,
                       ContextBudgetProperties budgets,
                       OrchestrationBudget orchestrationBudget) {
        this.chatCaller = chatCaller;
        this.promptAssembler = promptAssembler;
        this.budgets = budgets;
        this.orchestrationBudget = orchestrationBudget;
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
     * 写 subtask_0..n-1、subtaskAgent_0..n-1 与 subtaskCount。
     * 消耗经门控账本句柄按 roundtrip 增量记账（lead 是编排首调用，发起前账本为 0 不会熔断；
     * 极小预算下 usage 帧中途熔断时降级为「拆解失败」——退化为单子任务，交由后续熔断跳过，
     * 聚合注入降级说明）。lead 自身消耗计入账本供后续判定。
     */
    Map<String, Object> lead(OverAllState state, AtomicBoolean cancelled) {
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
        String content;
        try {
            content = predictLeadLogged(sessionId, objective, cancelled,
                    orchestrationBudget.ledgerHandle(state, true), leadTrace);
        } catch (BudgetLedger.BudgetExceededException e) {
            log.warn("[multi-agent][lead] 编排预算超限中止拆解（已消耗 {} / 上限 {}），退化为单子任务",
                    OrchestrationBudget.ledgerValue(state), budgets.getOrchestrationBudget());
            content = null; // 拆解产物缺失 → 退化为单个子任务=objective，执行前被熔断跳过
        }
        List<LeadOutputParser.Subtask> items = LeadOutputParser.parseSubtasks(content);
        if (items.isEmpty()) {
            // 拆解失败：退化为单个子任务=objective
            items.add(new LeadOutputParser.Subtask(objective, null));
        }
        Map<String, Object> updates = new HashMap<>();
        int n = Math.min(items.size(), MultiAgentGraphAgent.MAX_SUBTASKS);
        updates.put(MultiAgentGraphAgent.K_LEAD_SPAN, leadSpanId);
        updates.put(MultiAgentGraphAgent.K_SUBTASK_COUNT, n);
        for (int i = 0; i < n; i++) {
            LeadOutputParser.Subtask item = items.get(i);
            updates.put(MultiAgentGraphAgent.K_SUBTASK_PREFIX + i, item.desc());
            updates.put(MultiAgentGraphAgent.K_SUBTASK_AGENT_PREFIX + i, item.agent());
        }
        log.info("[multi-agent][lead] 拆解为 {} 个子任务，指派：{}", n,
                items.subList(0, n).stream().map(LeadOutputParser.Subtask::agent).toList());
        return updates;
    }

    /**
     * 子任务节点：读 subtask_i 与指派的 subtaskAgent_i，若存在则调用对应专家 ChatClient 生成 result_i。
     * 熔断下沉到 caller（方向 b）：门控账本句柄在调用发起前（零 HTTP）与每轮 roundtrip 的
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
        String sessionId = state.value(MultiAgentGraphAgent.K_SESSION_ID, String.class).orElse(null);
        // 轨迹贯通：派生调用挂同一执行链（turn/trace），parent_span=lead 的 span_id
        CallTrace trace = new CallTrace(state.value(MultiAgentGraphAgent.K_TURN_ID, String.class).orElse(null),
                state.value(MultiAgentGraphAgent.K_TRACE_ID, String.class).orElse(null),
                state.value(MultiAgentGraphAgent.K_LEAD_SPAN, String.class).orElse(null), null);
        String result;
        try {
            result = predictSubtask(sessionId, task, expert, toolEmitter, cancelled,
                    orchestrationBudget.ledgerHandle(state, true), trace);
        } catch (BudgetLedger.BudgetExceededException e) {
            log.warn("[multi-agent][subtask-{}] 编排 token 消费已达上限（{} / {}），跳过专家调用",
                    idx, OrchestrationBudget.ledgerValue(state), budgets.getOrchestrationBudget());
            Map<String, Object> updates = new HashMap<>();
            updates.put(MultiAgentGraphAgent.K_RESULT_PREFIX + idx, MultiAgentGraphAgent.SKIPPED_RESULT);
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
        for (int i = 0; i < n; i++) {
            String r = state.value(MultiAgentGraphAgent.K_RESULT_PREFIX + i, String.class).orElse(null);
            if (r == null || r.isBlank()) {
                continue; // 子任务失败（异常上抛）无结果
            }
            if (MultiAgentGraphAgent.SKIPPED_RESULT.equals(r)) {
                skipped++; // 预算熔断跳过：不计入真实结果，降级说明交代
                continue;
            }
            results.add(r);
        }
        String finalAnswer;
        if (results.isEmpty() && skipped == 0) {
            // 子任务全失败：兜底（原有行为）
            finalAnswer = state.value(MultiAgentGraphAgent.K_FINAL, String.class).orElse("（未生成最终回答）");
        } else {
            String user = OrchestrationPrompts.aggregateUserPrompt(results);
            if (skipped > 0) {
                user = orchestrationBudget.degradationNote(skipped, state) + "\n\n" + user;
            }
            if (liveTokens == null) {
                // 同步路径 cancelled 为 null（无取消语义），流式路径传共享断连标志
                finalAnswer = predictAggregate(sessionId, user, cancelled,
                        orchestrationBudget.ledgerHandle(state, false), trace);
            } else {
                finalAnswer = streamGuard.predictStreaming(sessionId, user, liveTokens, contentSent,
                        cancelled, orchestrationBudget.ledgerHandle(state, false), trace);
            }
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(MultiAgentGraphAgent.K_FINAL, finalAnswer);
        log.info("[multi-agent][aggregate] 汇总 {} 个子任务结果（预算超限跳过 {} 个）",
                results.size(), skipped);
        return updates;
    }

    /* ---------- ChatClient 单次调用 ---------- */

    /**
     * lead 拆解：按 agent 表 lead 行的提示词/模型执行（无配置时回退内置兜底）；目标超长尾截至 lead 预算。
     * cancelled 传节点共享断连标志（同步路径为 null）：调用前已置位直接抛取消异常（零 HTTP 请求），
     * 执行中置位在下一个 token 边界中止在途请求。budgetLedger 门控账本句柄（caller 发起前与
     * 每轮 roundtrip 熔断判定 + 增量记账；可 null）。
     */
    private String predictLead(String sessionId, String objective, AtomicBoolean cancelled,
                               BudgetLedger budgetLedger, CallTrace trace) {
        return chatCaller.call(sessionId, ROLE_LEAD, OrchestrationPrompts.LEAD_FALLBACK_PROMPT, "拆解目标：" + objective,
                null, new PromptBudgetAdvisor[]{PromptBudgetAdvisor.tail(budgets.getLeadBudget())},
                cancelled == null ? null : cancelled::get, budgetLedger, trace);
    }

    /** lead 拆解前日志埋点便于诊断专家指派（raw 输出统一记审计） */
    private String predictLeadLogged(String sessionId, String objective, AtomicBoolean cancelled,
                                     BudgetLedger budgetLedger, CallTrace trace) {
        String raw = predictLead(sessionId, objective, cancelled, budgetLedger, trace);
        log.info("[multi-agent][lead] raw 拆解输出: {}", raw.length() > 300 ? raw.substring(0, 300) + "..." : raw);
        return raw;
    }

    /**
     * 子任务执行：按指派专家查配置调用（cancelled 传节点共享断连标志，同步路径为 null——
     * 执行中置位时在途调用随令牌中止，不再烧完剩余 token）。budgetLedger 门控账本句柄：
     * caller 发起前与每轮 roundtrip 熔断判定 + 按 roundtrip 增量记账（可 null）。
     */
    private String predictSubtask(String sessionId, String task, String expert,
                                  java.util.function.Consumer<String> toolEmitter,
                                  AtomicBoolean cancelled,
                                  BudgetLedger budgetLedger, CallTrace trace) {
        // 未指派（lead 输出旧格式或漏 agent 字段）→ 回退 general：通用兜底且持有全量工具
        String resolved = (expert == null || expert.isBlank())
                ? AgentConstants.DEFAULT_AGENT : expert;
        // 专家 persona 与工具使用纪律经 PromptAssembler 统一组装（原硬编码拼接已删除）：
        // persona 作角色段兜底传入，工具索引/工具纪律/输出约定等段由调用器组装时追加
        String persona = promptAssembler.subtaskPersona(resolved);
        return chatCaller.call(sessionId, resolved, persona, task, toolEmitter,
                new PromptBudgetAdvisor[0], cancelled == null ? null : cancelled::get, budgetLedger, trace);
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
