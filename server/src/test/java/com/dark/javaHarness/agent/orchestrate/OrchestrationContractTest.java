package com.dark.javaHarness.agent.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 编排契约测试：冻结占位文案与状态键。
 *
 * <p>这些字面量是聚合提示词、进度播报、检查点续跑的共同契约；
 * 自研引擎替换 StateGraph 时必须保持不变，否则历史检查点与前端进度行都会失配。
 */
class OrchestrationContractTest {

    @Test
    void placeholderTextsAreStable() {
        assertEquals("（预算超限跳过：编排 token 消费已达上限）", MultiAgentGraphAgent.SKIPPED_RESULT);
        assertEquals("（子任务执行超时，已跳过）", MultiAgentGraphAgent.TIMEOUT_SKIPPED_RESULT);
        assertEquals("（子任务执行失败，已跳过）", MultiAgentGraphAgent.FAILED_RESULT);
    }

    @Test
    void stateKeysAreStable() {
        assertEquals("objective", MultiAgentGraphAgent.K_OBJECTIVE);
        assertEquals("subtask_", MultiAgentGraphAgent.K_SUBTASK_PREFIX);
        assertEquals("subtaskAgent_", MultiAgentGraphAgent.K_SUBTASK_AGENT_PREFIX);
        assertEquals("result_", MultiAgentGraphAgent.K_RESULT_PREFIX);
        assertEquals("final", MultiAgentGraphAgent.K_FINAL);
    }
}
