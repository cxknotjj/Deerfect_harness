package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.graph.MultiAgentGraphAgent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

/**
 * 子任务结局播报单测：节点返回前经 {@code recordOutcome} 登记最终 result
 * （成功内容或占位常量），引擎取到结果后调用 {@code report} 播报——
 * 失败/超时/预算占位不得误报为「完成」。
 */
class BranchProgressListenerTest {

    private static final String NODE_PREFIX = MultiAgentGraphAgent.SUBTASK_NODE_PREFIX;
    private static final String STATE_PREFIX = MultiAgentGraphAgent.K_SUBTASK_PREFIX;

    /** 建监听器 + 已订阅收集器；返回 [listener, rows] */
    private Object[] listenerWithRows() {
        Sinks.Many<String> events = Sinks.many().unicast().onBackpressureBuffer();
        List<String> rows = new CopyOnWriteArrayList<>();
        BranchProgressListener listener = new BranchProgressListener(events, NODE_PREFIX, STATE_PREFIX);
        events.asFlux().subscribe(rows::add);
        return new Object[]{listener, rows};
    }

    @Test
    void report_failedOutcome_broadcastsSkippedNotDone() {
        Object[] pair = listenerWithRows();
        BranchProgressListener listener = (BranchProgressListener) pair[0];
        List<String> rows = (List<String>) pair[1];
        listener.recordOutcome(0, SubtaskResults.FAILED);
        listener.report(0, null);

        assertEquals(1, rows.size());
        assertTrue(ProgressLine.decode(rows.get(0)).detail().contains("执行失败，已跳过"),
                "失败占位应播报失败而非完成：" + rows.get(0));
    }

    @Test
    void report_timeoutAndBudgetOutcomes_broadcastActualOutcome() {
        Object[] pair = listenerWithRows();
        BranchProgressListener listener = (BranchProgressListener) pair[0];
        List<String> rows = (List<String>) pair[1];
        listener.recordOutcome(0, SubtaskResults.TIMEOUT_SKIPPED);
        listener.report(0, null);
        assertTrue(ProgressLine.decode(rows.get(0)).detail().contains("执行超时"), rows.get(0));

        listener.recordOutcome(1, SubtaskResults.SKIPPED);
        listener.report(1, null);
        assertTrue(ProgressLine.decode(rows.get(1)).detail().contains("预算超限"), rows.get(1));
    }

    @Test
    void report_noRegistration_fallbackDeltaStillClassifies() {
        Object[] pair = listenerWithRows();
        BranchProgressListener listener = (BranchProgressListener) pair[0];
        List<String> rows = (List<String>) pair[1];
        // 未登记（节点异常路径由引擎写占位 delta）：按调用方传入的结果分类
        listener.report(0, SubtaskResults.FAILED);
        assertTrue(ProgressLine.decode(rows.get(0)).detail().contains("执行失败，已跳过"), rows.get(0));
    }

    @Test
    void report_successOrMissingResult_broadcastsDone() {
        Object[] pair = listenerWithRows();
        BranchProgressListener listener = (BranchProgressListener) pair[0];
        List<String> rows = (List<String>) pair[1];
        // 成功：登记真实结果（非占位）→ 完成
        listener.recordOutcome(0, "专家的真实回答");
        listener.report(0, null);
        assertEquals("第 1 个子任务完成", ProgressLine.decode(rows.get(0)).detail());
        // 无登记且无兜底结果（短路兜底路径）→ 完成兜底
        listener.report(0, null);
        assertEquals("第 1 个子任务完成", ProgressLine.decode(rows.get(1)).detail());
    }
}
