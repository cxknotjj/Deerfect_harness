package com.dark.javaHarness.agent.orchestrate.engine;

/**
 * 引擎输出事件：进度行（已是 ProgressLine 编码后的字符串）、聚合 token。
 */
public sealed interface EngineEvent {

    record Progress(String line) implements EngineEvent {
    }

    record Token(String text) implements EngineEvent {
    }
}
