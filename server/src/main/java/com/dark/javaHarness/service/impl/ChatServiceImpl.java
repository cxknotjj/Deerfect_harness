package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.service.ChatService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 聊天用例服务门面（超长类拆分 2026-11-05）：只做入口分发，不再承载流程细节。
 *
 * <p>职责按下沉组件划分：
 * <ul>
 *   <li>{@link RouteDecider}：路由判定 + RAG 预取汇合；</li>
 *   <li>{@link ChatTurnExecutor}：同步执行（含 COMPLEX 降级重答）；</li>
 *   <li>{@link ChatStreamPipeline}：流式执行、SSE 组装、断连与续跑；</li>
 *   <li>{@link TurnPersistence}：会话建档/绑定/写回/续跑校验；</li>
 *   <li>{@link ReplyDecorator}：回复后处理、语音 flag 与 RAG 出处。</li>
 * </ul>
 */
@Service
public class ChatServiceImpl implements ChatService {

    private final ChatTurnExecutor turnExecutor;
    private final ChatStreamPipeline streamPipeline;

    public ChatServiceImpl(ChatTurnExecutor turnExecutor, ChatStreamPipeline streamPipeline) {
        this.turnExecutor = turnExecutor;
        this.streamPipeline = streamPipeline;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return turnExecutor.execute(request);
    }

    @Override
    public Flux<String> streamReactive(ChatRequest request) {
        return streamPipeline.stream(request);
    }

    @Override
    public Flux<String> resume(String goalId) {
        return streamPipeline.resume(goalId);
    }
}
