package com.dark.javaHarness.agent;

import com.dark.javaHarness.advisor.ContextAssemblingAdvisor;
import com.dark.javaHarness.agent.agentic.AgentScopeModelFactory;
import com.dark.javaHarness.agent.agentic.SpringAiToolBridge;
import com.dark.javaHarness.agent.BranchProgressListener;
import com.dark.javaHarness.config.AgenticProperties;
import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.observability.LlmCallObserver;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.prompt.ToolLazyManager;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.observability.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.tool.trace.ToolCallTracer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

/**
 * Agentic SIMPLE 路径（路径 A）：以 AgentScope {@code ReActAgent} 驱动推理-行动循环，
 * 工具/模型/记忆/观测仍由项目既有设施提供。
 *
 * <p>与 {@link GeneralAssistantAgent} 的差异：后者是「单次 ChatClient 调用 + Spring AI 内部
 * 工具循环」，本类是显式 ReAct（多轮推理/工具、最大迭代次数、可中断）；两条路径输出契约一致
 * （内容 token + {@link ProgressLine} 进度行 + meta 由上层收口）。
 *
 * <p>关键边界：
 * <ul>
 *   <li>工具：Spring AI {@code ToolCallback}（含 MCP/沙箱）经 {@link SpringAiToolBridge} 注册进
 *       AgentScope Toolkit，不使用 AgentScope 自带 MCP client；预算/观测/懒加载装饰链照常生效；</li>
 *   <li>记忆：不启用 AgentScope AgentStateStore，每次请求把裁剪后的 MySQL 历史转成 Msg 列表注入，
 *       避免与 SessionService 双写；</li>
 *   <li>模型：AgentScope OpenAI 兼容扩展按 model_provider 行构建，Key 解析与 ChatClient 同源；</li>
 *   <li>观测：{@link LlmCallObserver} 记一轮 llm_call_log（usage 优先取模型末帧真实值）；</li>
 *   <li>取消：外层 Flux cancel → {@code agent.interrupt(RuntimeContext)}；</li>
 *   <li>回退：AgentScope 初始化失败（模型/Key/工具装配）时按开关回退 {@link GeneralAssistantAgent}，
 *       流已开始后不切换。</li>
 * </ul>
 */
public class AgentScopeGeneralAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeGeneralAgent.class);

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。你能记住本会话之前的对话内容，回答时结合历史上下文。";

    private final String agentName;
    private final AgentService agentService;
    private final SessionService memoryStore;
    private final PromptAssembler promptAssembler;
    private final ContextBudgetProperties budgets;
    private final ChatTimeoutProperties timeouts;
    private final AgenticProperties agentic;
    private final AgentScopeModelFactory modelFactory;
    private final AgentRequestSpecFactory specFactory;
    private final LlmCallObserver observer;
    /** ChatClient 路径回退实例（初始化失败时使用；null = 不回退） */
    private final Agent fallback;

    public AgentScopeGeneralAgent(String agentName,
                                  ChatClientRegistry clientRegistry,
                                  AgentService agentService,
                                  SessionService memoryStore,
                                  ToolAssignments toolAssignments,
                                  LlmCallRecorder recorder,
                                  ContextBudgetProperties budgets,
                                  ToolLazyManager lazyTools,
                                  PromptAssembler promptAssembler,
                                  SkillManager skillManager,
                                  KnowledgeRetriever knowledgeRetriever,
                                  ChatTimeoutProperties timeouts,
                                  AgentScopeModelFactory modelFactory,
                                  AgenticProperties agentic,
                                  Agent fallback) {
        this.agentName = agentName;
        this.agentService = agentService;
        this.memoryStore = memoryStore;
        this.promptAssembler = promptAssembler;
        this.budgets = budgets;
        this.timeouts = timeouts;
        this.agentic = agentic;
        this.modelFactory = modelFactory;
        this.fallback = fallback;
        this.specFactory = new AgentRequestSpecFactory(clientRegistry, promptAssembler, toolAssignments,
                lazyTools, skillManager, memoryStore, budgets, knowledgeRetriever, recorder);
        this.observer = new LlmCallObserver(recorder);
    }

    @Override
    public String name() {
        return agentName;
    }

    @Override
    public String execute(Goal goal) {
        String content = executeStreamReactive(goal)
                .filter(line -> !ProgressLine.isProgress(line))
                .collect(Collectors.joining())
                .block();
        return content == null ? "" : content;
    }

    @Override
    public void executeStream(Goal goal, Consumer<String> onToken) {
        if (onToken == null) {
            execute(goal);
            return;
        }
        executeStreamReactive(goal)
                .filter(line -> !ProgressLine.isProgress(line))
                .doOnNext(onToken)
                .blockLast();
    }

    @Override
    public Flux<String> executeStreamReactive(Goal goal) {
        return Flux.defer(() -> {
            try {
                return streamInternal(goal);
            } catch (Exception e) {
                if (agentic.isFallbackEnabled() && fallback != null) {
                    log.warn("[agentic-{}] AgentScope 初始化失败，回退 ChatClient 路径：{}", agentName, safe(e), e);
                    return fallback.executeStreamReactive(goal);
                }
                return Flux.error(e);
            }
        });
    }

    private Flux<String> streamInternal(Goal goal) {
        String sessionId = goal.sessionId();
        AgentConfig config = agentService.getAgentConfig(agentName)
                .orElse(new AgentConfig(null, null, null, null));
        String spanId = CallTrace.newSpanId();
        CallTrace trace = CallTrace.fromGoal(goal).withSpan(spanId);
        long start = System.currentTimeMillis();
        int maxAttempts = Math.max(1, agentic.getMaxAttempts());

        // 1) system（PromptAssembler + RAG）与历史（MySQL → 预算裁剪）复用 ChatClient 路径的组装面
        String system = specFactory.assembleSystem(config, sessionId, agentName,
                DEFAULT_SYSTEM_PROMPT, goal.objective(), trace);
        List<org.springframework.ai.chat.messages.Message> history = specFactory.loadHistory(sessionId);
        List<org.springframework.ai.chat.messages.Message> trimmed = history == null ? List.of()
                : new ContextAssemblingAdvisor(budgets.getHistoryBudget()).assemble(history);

        // 2) 工具：装饰后的 Spring AI ToolCallback → AgentScope ToolBase（进度行仍由装饰链发射）
        Sinks.Many<String> content = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<String> progress = Sinks.many().unicast().onBackpressureBuffer();
        List<ToolCallback> tools = specFactory.resolveTools(agentName, sessionId,
                row -> BranchProgressListener.tryEmitSerialized(progress, row), false, null);
        Toolkit toolkit = new Toolkit();
        for (ToolCallback tool : tools) {
            toolkit.registerTool(new SpringAiToolBridge(tool));
        }

        // 3) 模型与 ReAct 执行体
        Model model = modelFactory.build(config);
        ReActAgent agent = buildAgent(model, system, toolkit, config);
        List<Msg> messages = toAgentScopeMessages(trimmed);
        messages.add(new UserMessage(goal.objective()));
        RuntimeContext runtime = runtimeContext(sessionId, trace);

        // 4) 事件映射与观测
        StringBuilder collected = new StringBuilder();
        int[] usageTotals = new int[3];
        AtomicLong firstTokenAt = new AtomicLong();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        CallContext ctx = new CallContext(observer, sessionId, agentName, modelFactory.modelName(config),
                promptAssembler.attachmentsOf(agentName), trace.turnId(), trace.traceId(), spanId,
                trace.parentSpan());

        Flux<AgentEvent> events = agent.streamEvents(messages, runtime)
                .doOnNext(event -> onEvent(event, content, progress, collected, usageTotals, firstTokenAt, config))
                .doOnError(errorRef::set)
                .doFinally(sig -> record(ctx, start, collected, usageTotals, firstTokenAt, errorRef,
                        sig, maxAttempts, content, progress, completed));

        // events 必须被订阅才会驱动 ReAct 执行：以 thenMany 空流并入 merge（只做驱动，不产出 String）
        return Flux.merge(content.asFlux(), progress.asFlux(), events.thenMany(Flux.<String>empty()))
                .doOnCancel(() -> {
                    try {
                        agent.interrupt(runtime);
                        log.warn("[agentic-{}] 客户端断开，已中断 ReAct 执行", agentName);
                    } catch (Exception e) {
                        log.warn("[agentic-{}] 中断 ReAct 执行失败：{}", agentName, safe(e));
                    }
                });
    }

    private ReActAgent buildAgent(Model model, String system, Toolkit toolkit, AgentConfig config) {
        int maxIters = agentic.getMaxIters() > 0 ? agentic.getMaxIters() : 5;
        int readTimeout = timeouts.getReadTimeoutSeconds() == null
                ? 300 : timeouts.getReadTimeoutSeconds();
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(system)
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .modelExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(readTimeout))
                        .maxAttempts(Math.max(1, agentic.getMaxAttempts()))
                        .initialBackoff(Duration.ofMillis(500))
                        .maxBackoff(Duration.ofSeconds(4))
                        .backoffMultiplier(2.0)
                        .build());
        if (budgets.getMaxTokensFinal() > 0) {
            builder.generateOptions(GenerateOptions.builder()
                    .maxTokens(budgets.getMaxTokensFinal())
                    .build());
        }
        return builder.build();
    }

    private static RuntimeContext runtimeContext(String sessionId, CallTrace trace) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .userId("anonymous")
                .sessionId(sessionId);
        if (trace.turnId() != null) {
            builder.put(ToolCallTracer.CTX_TURN_ID, trace.turnId());
        }
        if (trace.traceId() != null) {
            builder.put(ToolCallTracer.CTX_TRACE_ID, trace.traceId());
        }
        if (trace.spanId() != null) {
            builder.put(ToolCallTracer.CTX_PARENT_SPAN, trace.spanId());
        }
        return builder.build();
    }

    private void onEvent(AgentEvent event, Sinks.Many<String> content, Sinks.Many<String> progress,
                         StringBuilder collected, int[] usageTotals,
                         AtomicLong firstTokenAt, AgentConfig config) {
        if (event instanceof TextBlockDeltaEvent delta) {
            firstTokenAt.compareAndSet(0, System.currentTimeMillis());
            collected.append(delta.getDelta());
            BranchProgressListener.tryEmitSerialized(content, delta.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent thinking) {
            if (Boolean.TRUE.equals(config.thinking())) {
                BranchProgressListener.tryEmitSerialized(progress,
                        ProgressLine.encode("思考 · " + agentName, thinking.getDelta()));
            }
        } else if (event instanceof ModelCallEndEvent end) {
            // ReAct 多轮工具调用会多次 MODEL_CALL：usage 逐次累加，避免只记末轮导致低估
            ChatUsage usage = end.getUsage();
            if (usage != null) {
                usageTotals[0] += nullSafe(usage.getInputTokens());
                usageTotals[1] += nullSafe(usage.getOutputTokens());
                usageTotals[2] += nullSafe(usage.getTotalTokens());
            }
        } else if (event instanceof AgentResultEvent result) {
            // 事件流偶发只回放文本前缀，最终 Msg 才含完整回答：补发未上屏的尾部（不重复已输出部分）
            String text = result.getResult() == null ? null : result.getResult().getTextContent();
            if (text != null && text.length() > collected.length() && text.startsWith(collected.toString())) {
                String suffix = text.substring(collected.length());
                collected.append(suffix);
                BranchProgressListener.tryEmitSerialized(content, suffix);
            }
        } else if (event instanceof ExceedMaxItersEvent) {
            BranchProgressListener.tryEmitSerialized(progress,
                    ProgressLine.encode("agentic", "达到最大迭代轮次，输出当前结果"));
            // 内部控制信号（不对用户展示）：ReAct 未收敛，ChatService 据此决定是否升级 Graph 编排
            BranchProgressListener.tryEmitSerialized(progress,
                    ProgressLine.encode(ProgressLine.STAGE_AGENTIC_EXHAUSTED, "max-iters"));
        }
    }

    private void record(CallContext ctx, long start, StringBuilder collected,
                        int[] usageTotals, AtomicLong firstTokenAt,
                        AtomicReference<Throwable> errorRef, SignalType sig, int maxAttempts,
                        Sinks.Many<String> content, Sinks.Many<String> progress,
                        AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        Throwable err = errorRef.get();
        if (err == null && sig == SignalType.CANCEL) {
            err = new IllegalStateException("客户端断开（stream cancel）");
        } else if (err == null && sig == SignalType.ON_ERROR) {
            err = new IllegalStateException("流式异常终止");
        }
        try {
            if (err == null) {
                ctx.ok(null, start, collected.toString(), toUsage(usageTotals), firstTokenAt.get(),
                        1, maxAttempts);
                BranchProgressListener.tryCompleteSerialized(content);
                BranchProgressListener.tryCompleteSerialized(progress);
            } else {
                ctx.error(start, asException(err), 1, maxAttempts);
                content.tryEmitError(err);
                progress.tryEmitError(err);
            }
        } catch (Exception e) {
            log.warn("[agentic-{}] 收尾失败（不影响主链路）：{}", agentName, safe(e));
        }
    }

    private static List<Msg> toAgentScopeMessages(
            List<org.springframework.ai.chat.messages.Message> history) {
        List<Msg> messages = new ArrayList<>(history.size());
        for (org.springframework.ai.chat.messages.Message message : history) {
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (message instanceof org.springframework.ai.chat.messages.AssistantMessage) {
                messages.add(new AssistantMessage(text));
            } else if (message instanceof org.springframework.ai.chat.messages.UserMessage) {
                messages.add(new UserMessage(text));
            }
            // 历史中的 system/tool 消息不注入：system 由 sysPrompt 承载，工具结果不入历史快照
        }
        return messages;
    }

    private static Usage toUsage(int[] totals) {
        if (totals[2] == 0 && totals[0] == 0 && totals[1] == 0) {
            return null;
        }
        return new Usage() {
            @Override
            public Integer getPromptTokens() {
                return totals[0];
            }

            @Override
            public Integer getCompletionTokens() {
                return totals[1];
            }

            @Override
            public Integer getTotalTokens() {
                return totals[2];
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private static Exception asException(Throwable t) {
        return t instanceof Exception ex ? ex : new IllegalStateException(String.valueOf(t), t);
    }

    private static String safe(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
