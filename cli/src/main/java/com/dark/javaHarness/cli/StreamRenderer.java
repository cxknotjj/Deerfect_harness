package com.dark.javaHarness.cli;

import com.dark.javaHarness.cli.api.ChatApiClient;
import com.dark.javaHarness.cli.render.TerminalRenderer;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.dto.KnowledgeSource;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * 回合执行与逐帧渲染（自 {@link ChatCli} 拆出，超长类拆分 2026-11-05）：
 * SSE 进度帧解析（goal/编排阶段识别）、token 流渲染、回合小结与会话 ID 记忆。
 * 命令解析不在本类，由 {@link CommandDispatcher} 负责。
 */
final class StreamRenderer {

    // 未知字段宽容：与服务端版本错开时（DTO 新增字段）仍可解析
    private static final ObjectMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final ChatApiClient api;
    private final String baseUrl;
    private final TerminalRenderer renderer;
    private final ChatSessionState state;
    private final Supplier<PrintStream> ui;

    /** 续跑目标变化后回写持久化文件（由 ChatCli 提供，失败仅提示不影响主流程） */
    private final Runnable persistResumeState;

    StreamRenderer(ChatApiClient api, String baseUrl, TerminalRenderer renderer,
                   ChatSessionState state, Supplier<PrintStream> ui, Runnable persistResumeState) {
        this.api = api;
        this.baseUrl = baseUrl;
        this.renderer = renderer;
        this.state = state;
        this.ui = ui;
        this.persistResumeState = persistResumeState;
    }

    /** 发送一条消息到主服务 /api/chat/stream（SSE 流式） */
    void send(String message) {
        runTurn((onToken, onProgress) ->
                api.chatStream(message, state.sessionId(), state.agentId(), state.routing(), onToken, onProgress));
    }

    /** 断点续跑指定 goal（SSE 流式；提示文案由调用方在此之前打印） */
    void resume(String goalId) {
        runTurn((onToken, onProgress) -> api.resumeStream(goalId, onToken, onProgress));
    }

    /** SSE 流式调用函数签名：一次调用产出完整回合（token/progress 回调 + 返回 meta） */
    @FunctionalInterface
    private interface SseCall {
        ChatResponse invoke(Consumer<String> onToken, Consumer<String> onProgress) throws IOException;
    }

    /**
     * 跑一个完整回合（send 与 /resume 共用）：进度 spinner 原位刷新、token 流式 Markdown、
     * 回合小结；记住服务端返回的会话ID延续多轮上下文。
     */
    private void runTurn(SseCall call) {
        renderer.beginTurn();
        // 流中见到的 goalId（goal 进度事件到达即记录——不等回合成功，断开前也已留存）
        AtomicReference<String> seenGoalId = new AtomicReference<>();
        try {
            ChatResponse resp = call.invoke(renderer::onToken,
                    data -> {
                        String stage = "";
                        String detail = data;
                        try {
                            var node = MAPPER.readTree(data);
                            stage = node.path("stage").asText("");
                            detail = node.path("detail").asText("");
                        } catch (tools.jackson.core.JacksonException ignored) {
                            // 解析失败则 detail 原样展示，不中断
                        }
                        if ("goal".equals(stage) && !detail.isBlank()) {
                            // goalId 到达即记录并持久化：CLI 断开/重启后仍可 /resume
                            seenGoalId.set(detail);
                            state.lastOrchestratedGoalId(detail);
                            persistResumeState.run();
                        }
                        renderer.onProgress(stage, detail);
                    });
            // 记住服务端返回的会话ID，后续请求携带以延续多轮上下文
            if (resp.sessionId() != null && !resp.sessionId().isBlank()) {
                state.sessionId(resp.sessionId());
                persistResumeState.run();
            }
            boolean ok = "SUCCEEDED".equals(resp.status());
            String goalId = resp.goalId() != null && !resp.goalId().isBlank()
                    ? resp.goalId() : seenGoalId.get();
            renderer.endTurn(ok, ok ? null : goalId + " " +
                    (resp.error() == null ? "（无详细信息）" : resp.error()));
            if (ok) {
                ui.get().println("\033[90m（会话 " + state.sessionId() + " / " + goalId + "）\033[0m");
                // RAG 知识出处尾注（meta.sources：本次会话最近一次知识命中的文档；无命中不打印）
                List<KnowledgeSource> sources = resp.sources();
                if (sources != null && !sources.isEmpty()) {
                    StringBuilder sb = new StringBuilder("\033[90m来源: ");
                    for (int i = 0; i < sources.size(); i++) {
                        if (i > 0) {
                            sb.append(" · ");
                        }
                        KnowledgeSource s = sources.get(i);
                        sb.append("【出处").append(i + 1).append("】").append(s.docName());
                    }
                    ui.get().println(sb.append("\033[0m").toString());
                }
            }
        } catch (ConnectException e) {
            renderer.endTurn(false, "无法连接主服务 " + baseUrl);
            ui.get().println("请先启动主进程: mvn -s .mvn/settings.xml spring-boot:run");
        } catch (IOException e) {
            if (ChatApiClient.isGoalCompleted(e)) {
                // 服务端判定任务已完成（409 无需续跑）：清掉过期的本地续跑记录，下次启动不再提示
                state.lastOrchestratedGoalId(null);
                persistResumeState.run();
                renderer.endTurn(false, "该任务已完成，无需续跑（已清除本地续跑记录）");
                return;
            }
            renderer.endTurn(false, e.getMessage());
        }
    }
}
