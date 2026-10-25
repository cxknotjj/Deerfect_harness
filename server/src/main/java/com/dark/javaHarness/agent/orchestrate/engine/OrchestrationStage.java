package com.dark.javaHarness.agent.orchestrate.engine;

/**
 * 编排检查点阶段，顺序即续跑推进方向。
 */
public enum OrchestrationStage {
    LEAD_DONE,
    SUBTASKS_DONE,
    DONE
}
