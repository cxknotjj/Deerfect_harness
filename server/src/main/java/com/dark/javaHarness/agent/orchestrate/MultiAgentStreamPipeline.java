package com.dark.javaHarness.agent.orchestrate;

import com.dark.javaHarness.agent.orchestrate.engine.EngineEvent;
import reactor.core.publisher.Flux;

/**
 * 多 Agent 编排的流式管道：把自研引擎的 {@link EngineEvent} 映射为 SSE 行。
 *
 * <p>映射规则（与旧「stream 主干帧 + 生命周期钩子旁路」输出逐字一致）：
 * 进度行原样透传（{@link com.dark.javaHarness.agent.ProgressLine} 线协议不变），
 * 聚合 token 原样透传；异常由引擎补「异常，已回退」进度行后以包装异常上抛，
 * 入口层据 complex-fallback 开关决定降级重答或按 FAILED 收尾。
 */
final class MultiAgentStreamPipeline {

    /** 引擎事件 → SSE 行（进度行/token 均为字符串，不做二次编码） */
    Flux<String> run(Flux<EngineEvent> events) {
        return events.map(event -> event instanceof EngineEvent.Progress p
                ? p.line()
                : ((EngineEvent.Token) event).text());
    }
}
