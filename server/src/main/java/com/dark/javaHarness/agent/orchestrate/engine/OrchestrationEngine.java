package com.dark.javaHarness.agent.orchestrate.engine;

import com.dark.javaHarness.domain.Goal;
import reactor.core.publisher.Flux;

/**
 * 多 Agent 编排引擎：同步、流式、断点续跑三个入口。
 */
public interface OrchestrationEngine {

    String execute(Goal goal);

    Flux<EngineEvent> stream(Goal goal);

    Flux<EngineEvent> resume(Goal goal);
}
