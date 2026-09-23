package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.advisor.LlmRequestLogAdvisor;
import com.dark.javaHarness.config.ChatTimeoutProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.domain.RouteDecision;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.RouteJudge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 基于 LLM 的主 Agent 路由判断器。
 *
 * <p>用一次轻量 LLM 调用判断请求简单/复杂：系统提示词要求模型严格输出
 * JSON（{@code {"route":"simple"}} 或 {@code {"route":"complex"}}），
 * 解析出 route 字段归一化为 {@link RouteDecision}。
 *
 * <p>兜底策略（TODO ⑤）：调用异常 / 超时 / 返回非 JSON / 解析失败时
 * 一律返回 {@link RouteDecision#SIMPLE}，且不向调用方抛出——宁可简单，不阻塞请求。
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
            "你是 Harness 的主路由判断器。判断一条用户请求应该走「简单」还是「复杂」路径。\n"
            + "只输出一行 JSON，不要任何解释、前后缀。格式严格为："
            + "{\"route\":\"simple\"} 或 {\"route\":\"complex\"}\n"
            + "- simple：无需工具、无需拆分子任务，单次回答即可（如问候、闲聊、简短问答、讲笑话、简单解释）。\n"
            + "- complex：需联网搜索、需执行代码、多步骤处理、需拆分为多个子任务（如调研竞品并输出报告、规划并执行一个完整项目）。";

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

    @Override
    public RouteDecision judge(String message, String sessionId) {
        if (message == null || message.isBlank()) {
            log.info("[route] message 为空 -> SIMPLE");
            return RouteDecision.SIMPLE;
        }
        try {
            // 生效配置解析一次（model + prompt）：表驱动优先，回退语义见 settings()
            JudgeSettings settings = settings();
            // 逐次尝试记录：每次真实 LLM 调用（含重试）单独落 llm_call_log，
            // 失败尝试带真实错误描述，不再只记重试链的聚合结果（重试曾完全不可见）
            String content = retry.executeWithRetry(() -> doCall(message, settings),
                    (attempt, durationMs, err) -> {
                        record(durationMs, err == null, err, message, settings, sessionId);
                        // 失败即丢池：连接可能已成网络黑洞，让重试拿到全新连接而不是再挂一次
                        if (err != null) {
                            clientRegistry.invalidateLightweight(settings.model());
                        }
                    });
            return parse(content);
        } catch (Exception e) {
            // 判断失败（含重试耗尽或解析失败）不阻塞请求，兜底走简单路径
            log.warn("[route] 判断失败，兜底 SIMPLE：{}", safeMessage(e));
            return RouteDecision.SIMPLE;
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
                .system(settings.prompt())
                .user(message)
                .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(settings.model()).build())
                // 发起前观测：与 agent 链路同款日志（此前 route-judge 在发起瞬间无任何痕迹）
                .advisors(new LlmRequestLogAdvisor(ROUTE_AGENT, null))
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText();
    }

    /** judge 单次尝试观测落库（携带 sessionId 进会话轨迹；model/prompt 记实际生效值；错误经原因链展开） */
    private void record(long durationMs, boolean ok, Throwable e, String message,
                        JudgeSettings settings, String sessionId) {
        if (recorder == null) {
            return;
        }
        int promptTokens = LlmCallRecorder.estimateTokens(settings.prompt())
                + LlmCallRecorder.estimateTokens(message);
        recorder.record(new LlmCallLog(sessionId, ROUTE_AGENT, settings.model(), false, ok,
                promptTokens, null, null, true,
                durationMs, LlmCallRecorder.describeError(e),
                null, null, null, null, null, null, null, null));
    }

    /** 解析 LLM 返回内容中的 route 字段；非法/缺失一律兜底 SIMPLE。 */
    private RouteDecision parse(String content) {
        if (content == null || content.isBlank()) {
            log.warn("[route] 返回为空，兜底 SIMPLE");
            return RouteDecision.SIMPLE;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(content);
            String route = node.path("route").asText(null);
            RouteDecision decision = RouteDecision.fromRaw(route);
            log.info("[route] 解析结果 route='{}' -> {}", route, decision);
            return decision;
        } catch (Exception e) {
            log.warn("[route] 返回非 JSON，兜底 SIMPLE | content='{}'", content.replaceAll("[\\r\\n]+", " "));
            return RouteDecision.SIMPLE;
        }
    }

    private static String safeMessage(Exception e) {
        return LlmCallRecorder.describeError(e);
    }
}