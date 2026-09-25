package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.domain.dto.KnowledgeSource;
import com.dark.javaHarness.domain.dto.SseMeta;
import com.dark.javaHarness.enums.SseProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * SSE 行编码纯函数集（自 {@link ChatServiceImpl} 拆出，超长类拆分 2026-09-25）：
 * 进度/内容行 → SSE 事件帧、agent 归属进度行注入、meta 收尾事件组装。
 * 流的生命周期编排（写回/错误收尾）仍属宿主 {@code toSseBody}，此处只做无状态编码。
 */
final class SseEncoder {

    /** Jackson 序列化（SseMeta/StageRow 为 record，默认序列化即可） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SseEncoder() {
    }

    /**
     * 流首插入 agent 归属进度行（stage=agent, detail=agentName）：CLI 在首个回答 token 前
     * 渲染「agentName&gt; 」前缀，与用户侧「你&gt; 」提示符对称——智能分流下实际路由的 Agent
     * 只有服务端知道。进度行走旁路协议，不计入会话摘要与 goal.summary。
     */
    static Flux<String> withAgentProgress(String agentName, Flux<String> agentTokens) {
        return Flux.concat(Flux.just(ProgressLine.encode("agent", agentName)), agentTokens);
    }

    /**
     * 把 Agent 流出的一行转成 SSE 行序列：
     * - 进度行 {@code \u0000stage\u0001detail} → {@code event: progress} + {@code data: {"stage":..,"detail":..}}
     * - 其它（内容 token）→ {@code event: token} + {@code data: <token>}
     *
     * <p>progress 的 data JSON 直接用 Jackson 序列化 record，转义交给它，不再手写。
     */
    static Flux<String> toSseRows(String row) {
        ProgressLine.StageRow p = ProgressLine.decode(row);
        if (p == null) {
            // 内容行：裸换行会把一条 data 断成多个物理行，CLI 只认前缀行会丢内容——必须行内转义（可逆）。
            // event: token 必须显式声明：SSE 的 event 字段粘滞，progress 块之后不带 event: 的
            // data 行会被客户端误归入 progress（token 被吞、CLI 显示 0 字）。
            return Flux.just("event: " + SseProtocol.EVENT_TOKEN
                    + "\ndata: " + SseProtocol.escapeLineBreaks(row));
        }
        try {
            // event 与 data 必须在同一元素内：MVC 逐元素 flush，拆成两个元素会被其它事件的行交叉插入
            return Flux.just("event: " + SseProtocol.EVENT_PROGRESS + "\ndata: " + OBJECT_MAPPER.writeValueAsString(p));
        } catch (Exception e) {
            return Flux.just("event: " + SseProtocol.EVENT_PROGRESS + "\ndata: {\"stage\":\"?\",\"detail\":\"?\"}");
        }
    }

    /**
     * 组装 SSE meta 事件单元素块（event+data 同元素，保证成对不被交叉）：{@code event: meta\n data: {json}}。
     * sources 由调用方按各自求值时机（组装期/错误期）算好传入，本类不做检索。
     */
    static Flux<String> metaEvent(String sessionId, boolean newSession, String goalId,
                                  String status, String error, List<KnowledgeSource> sources) {
        SseMeta meta = new SseMeta(sessionId, newSession, goalId, status, error, sources);
        try {
            return Flux.just("event: " + SseProtocol.EVENT_META + "\ndata: " + OBJECT_MAPPER.writeValueAsString(meta));
        } catch (Exception e) {
            return Flux.just("event: " + SseProtocol.EVENT_META + "\ndata: {\"error\":\"meta serialization failed\"}");
        }
    }
}
