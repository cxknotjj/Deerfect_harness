package com.dark.javaHarness.service.impl.route;

import com.dark.javaHarness.observability.LlmRequestLogAdvisor;
import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.domain.RouteDecision;
import com.dark.javaHarness.domain.RoutePlan;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.RouteJudge;
import com.dark.javaHarness.observability.LlmCallRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 两段式路径判断器：规则短路（多工作流措辞 / 短消息）+ 灰区轻量 LLM 判定 + 置信度策略。
 *
 * <p>LLM 系统提示词按任务形态判断（Agentic = 单工作流；Graph = ≥2 个可独立并行工作流），
 * 输出富 JSON：{@code route/confidence/workstreams/deliverables/reason}；
 * route 归一化为 {@link RouteDecision}，Graph 需 ≥2 工作流且置信度 ≥0.8 才保留。
 *
 * <p>兜底策略：调用异常 / 超时 / 返回非 JSON / 解析失败时一律返回 Agentic，
 * 且不向调用方抛出——宁可轻量，不阻塞请求。
 */
@Service
public class LlmRouteJudge implements RouteJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmRouteJudge.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 判定用模型 key 回退默认：agent 表 route-judge 行（V22 注册，is_internal=1）存在时以
     * 表配置为准（改库即生效）；无行/异常时回退此常量，行为与表驱动化之前一致。
     */
    private static final String ROUTE_MODEL = "qwen3.8-27b";

    /** 观测/落库用的调用方标识（llm_call_log.agent_name 与发起日志一致），兼作 agent 表行名 */
    private static final String ROUTE_AGENT = "route-judge";

    /** 判定提示词回退默认：与 V22 迁移写入 agent 表 route-judge 行的 prompt 原文一致 */
    private static final String SYSTEM_PROMPT =
            "你是 Harness 的主路径判断器。判断一条用户请求应走「Agentic」还是「Graph 编排」路径。\n"
            + "Agentic：单一工作流，可由一个 Agent 自主推理-行动、按需多步调用工具。\n"
            + "Graph：存在至少 2 个可独立并行推进的工作流，或多份独立交付物；只有并行收益大于协调成本时才选。\n"
            + "不要按消息长短或主观难度判断。";

    /** 无论 agent 表 prompt 是否更新，都追加输出契约，保证模型返回置信度与工作流字段 */
    private static final String OUTPUT_CONTRACT =
            "\n\n只输出一行 JSON，不要任何解释、前后缀。格式严格为："
            + "{\"route\":\"agentic|graph\",\"confidence\":0.0,\"workstreams\":[\"...\"],"
            + "\"deliverables\":[\"...\"],\"reason\":\"...\"}\n"
            + "confidence 为 0~1；workstreams 为可独立并行推进的工作流列表；"
            + "无法判断时 route=agentic、confidence=0。";

    /** 强多工作流信号：命中即规则短路到 Graph（不覆盖显式指定） */
    private static final Pattern GRAPH_SIGNAL = Pattern.compile(
            "分别|各写|各生成|各输出|各一份|多份|逐一|逐个|同时调研|同时分析|多个报告|多个方案");

    /** 短消息直接 Agentic 的字符阈值（避免为寒暄/单句问答付判定调用） */
    private static final int TRIVIAL_MAX_CHARS = 12;

    /** Graph 置信度门槛：低于该值先走 Agentic，由运行时升级兜底 */
    private static final double GRAPH_CONFIDENCE_THRESHOLD = 0.8;

    private final ChatClientRegistry clientRegistry;
    /** LLM 调用观测记录器：judge 调用耗时/token 也落 llm_call_log（agent_name='route-judge'） */
    private final LlmCallRecorder recorder;
    /** agent 表配置读取：route-judge 行（V22，is_internal=1）提供 model/prompt，改库即生效 */
    private final AgentConfigProvider agentConfigProvider;
    /** 模型调用重试策略（最多执行 3 次、指数退避；重试耗尽或不可重试的解析错误才兜底 SIMPLE） */
    private final com.dark.javaHarness.agent.LlmRetry retry;

    /** LLM 超时配置：判定调用用独立短读超时（app.chat.timeouts.judge-read-timeout-seconds） */
    private final ChatTimeoutProperties timeouts;

    public LlmRouteJudge(ChatClientRegistry clientRegistry, LlmCallRecorder recorder,
                         ChatTimeoutProperties timeouts, AgentConfigProvider agentConfigProvider) {
        this.clientRegistry = clientRegistry;
        this.recorder = recorder;
        this.timeouts = timeouts;
        this.agentConfigProvider = agentConfigProvider;
        this.retry = new com.dark.javaHarness.agent.LlmRetry();
    }

    /** 本次判定的生效配置：模型 key + 判定提示词（表驱动，回退默认见各常量注释） */
    private record JudgeSettings(String model, String prompt) {
    }

    /**
     * 解析生效配置：agent 表 route-judge 行（V22 注册）的 model/prompt 优先，
     * 无行/字段空白/查询异常逐项回退内置常量——judge 不因配置读取失败而阻塞。
     */
    private JudgeSettings settings() {
        try {
            return agentConfigProvider.getAgentConfig(ROUTE_AGENT)
                    .map(cfg -> new JudgeSettings(
                            cfg.model() == null || cfg.model().isBlank() ? ROUTE_MODEL : cfg.model(),
                            cfg.prompt() == null || cfg.prompt().isBlank() ? SYSTEM_PROMPT : cfg.prompt()))
                    .orElseGet(() -> new JudgeSettings(ROUTE_MODEL, SYSTEM_PROMPT));
        } catch (Exception e) {
            log.warn("[route] 读取 route-judge 配置失败，回退内置默认：{}", safeMessage(e));
            return new JudgeSettings(ROUTE_MODEL, SYSTEM_PROMPT);
        }
    }

    /**
     * 带规则与置信度策略的路径决策：先做强信号规则短路与短消息短路，灰区再调 LLM。
     * LLM 结果经置信度策略收敛：Graph 需 ≥2 个工作流且置信度 ≥0.8，
     * 否则先走 Agentic，避免为单工作流支付编排成本。
     */
    @Override
    public RoutePlan plan(String message, String sessionId, String turnId) {
        if (message == null || message.isBlank()) {
            return RoutePlan.agentic(1.0, "message empty", RoutePlan.Source.RULE);
        }
        if (GRAPH_SIGNAL.matcher(message).find()) {
            RoutePlan plan = RoutePlan.graph(0.9, List.of(), List.of(),
                    "rule: multi-workstream phrasing", RoutePlan.Source.RULE);
            log.info("[route] plan={}", plan);
            return plan;
        }
        if (message.trim().length() <= TRIVIAL_MAX_CHARS) {
            RoutePlan plan = RoutePlan.agentic(0.95, "rule: short single-turn message",
                    RoutePlan.Source.RULE);
            log.info("[route] plan={}", plan);
            return plan;
        }
        RoutePlan plan = judgeWithConfidence(message, sessionId, turnId);
        if (plan.graph() && plan.workstreams().size() < 2) {
            plan = RoutePlan.agentic(plan.confidence(), "llm: workstreams<2, collapse to agentic",
                    RoutePlan.Source.FALLBACK);
        } else if (plan.graph() && plan.confidence() < GRAPH_CONFIDENCE_THRESHOLD) {
            plan = RoutePlan.agentic(plan.confidence(), "llm: confidence below graph threshold",
                    RoutePlan.Source.FALLBACK);
        }
        log.info("[route] plan={}", plan);
        return plan;
    }

    private RoutePlan judgeWithConfidence(String message, String sessionId, String turnId) {
        if (message == null || message.isBlank()) {
            log.info("[route] message 为空 -> agentic");
            return RoutePlan.agentic(1.0, "message empty", RoutePlan.Source.RULE);
        }
        try {
            // 生效配置解析一次（model + prompt）：表驱动优先，回退语义见 settings()
            JudgeSettings settings = settings();
            // 逐次尝试记录：每次真实 LLM 调用（含重试）单独落 llm_call_log，
            // 失败尝试带真实错误描述，不再只记重试链的聚合结果（重试曾完全不可见）
            String content = retry.executeWithRetry(() -> doCall(message, settings),
                    (attempt, durationMs, err) -> {
                        record(durationMs, err == null, err, message, settings, sessionId, turnId);
                        // 失败即丢池：连接可能已成网络黑洞，让重试拿到全新连接而不是再挂一次
                        if (err != null) {
                            clientRegistry.invalidateLightweight(settings.model());
                        }
                    });
            return parse(content);
        } catch (Exception e) {
            // 判断失败（含重试耗尽或解析失败）不阻塞请求，兜底走 Agentic 路径
            log.warn("[route] 判断失败，兜底 Agentic：{}", safeMessage(e));
            return RoutePlan.agentic(0.0, "judge failed: " + safeMessage(e), RoutePlan.Source.FALLBACK);
        }
    }

    /** 单次 LLM 路由调用（不包含解析，可被重试）；返回模型原始输出文本。生效配置由 settings() 解析传入 */
    private String doCall(String message, JudgeSettings settings) {
        // 轻量客户端：短读超时（带 SIMPLE 兜底，无需等长回答口径的 300s）+ 不挂默认工具；
        // 客户端按生效模型名缓存（丢池 key 与此一致）
        ChatClient client = clientRegistry.getLightweightByModel(settings.model(),
                timeouts == null ? null : timeouts.getJudgeReadTimeoutSeconds());
        // 请求级显式携带 model：ChatClientFactory 的 defaultOptions 不含模型名，
        // 缺失时 DashScope 返回 400「you must provide a model parameter」（与
        // AgentChatCaller/GeneralAssistantAgent 同款约定）
        return client.prompt()
                .system(settings.prompt() + OUTPUT_CONTRACT)
                .user(message)
                .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(settings.model()))
                // 发起前观测：与 agent 链路同款日志（此前 route-judge 在发起瞬间无任何痕迹）
                .advisors(new LlmRequestLogAdvisor(ROUTE_AGENT, null))
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText();
    }

    /**
     * judge 单次尝试观测落库（携带 sessionId 进会话轨迹、turnId 归因到轮次；
     * 不属于 Agent 执行树：trace/span 落 NULL。model/prompt 记实际生效值；错误经原因链展开）
     */
    private void record(long durationMs, boolean ok, Throwable e, String message,
                        JudgeSettings settings, String sessionId, String turnId) {
        if (recorder == null) {
            return;
        }
        int promptTokens = LlmCallRecorder.estimateTokens(settings.prompt())
                + LlmCallRecorder.estimateTokens(message);
        // 发起时刻由耗时反推（重试回调只给耗时；回调与耗时计算同一时钟读数，差值即发起瞬间）
        long start = System.currentTimeMillis() - durationMs;
        recorder.record(new LlmCallLog(sessionId, ROUTE_AGENT, settings.model(), false, ok,
                promptTokens, null, null, true,
                durationMs, LlmCallRecorder.describeError(e),
                null, null, null, null, null, null, null, null,
                turnId, null, null, null, LlmCallRecorder.startedAt(start)));
    }

    /** 解析 LLM 返回内容中的 route/confidence/workstreams；非法/缺失一律兜底 Agentic。 */
    private RoutePlan parse(String content) {
        if (content == null || content.isBlank()) {
            log.warn("[route] 返回为空，兜底 Agentic");
            return RoutePlan.agentic(0.0, "empty judge response", RoutePlan.Source.FALLBACK);
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(content);
            String route = node.path("route").asText(null);
            double confidence = node.path("confidence").asDouble(0.6);
            List<String> workstreams = stringArray(node, "workstreams");
            List<String> deliverables = stringArray(node, "deliverables");
            String reason = node.path("reason").asText("");
            RouteDecision decision = RouteDecision.fromRaw(route);
            RoutePlan plan = new RoutePlan(decision, confidence, workstreams, deliverables,
                    reason.isBlank() ? "llm decision" : reason, RoutePlan.Source.LLM);
            log.info("[route] 解析结果 route='{}' confidence={} workstreams={} -> {}",
                    route, plan.confidence(), plan.workstreams(), plan.decision());
            return plan;
        } catch (Exception e) {
            log.warn("[route] 返回非 JSON，兜底 Agentic | content='{}'",
                    content.replaceAll("[\\r\\n]+", " "));
            return RoutePlan.agentic(0.0, "invalid judge json", RoutePlan.Source.FALLBACK);
        }
    }

    /** JSON 数组字段 → 字符串列表；缺失/非数组返回空列表。 */
    private static List<String> stringArray(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(array.size());
        array.forEach(item -> {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        });
        return values;
    }

    private static String safeMessage(Exception e) {
        return LlmCallRecorder.describeError(e);
    }
}