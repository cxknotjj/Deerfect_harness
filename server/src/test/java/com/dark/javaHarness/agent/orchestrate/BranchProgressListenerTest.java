package com.dark.javaHarness.agent.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.agent.ProgressLine;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

/**
 * 子任务结局播报单测：graph-core 并行分支的 after 钩子拿到的是节点输入态快照
 * （本节点写入的 result 键未合并），结局分类依赖节点返回前的 {@code recordOutcome}
 * 登记——失败/超时/预算占位不得误报为「完成」。
 */
class BranchProgressListenerTest {

    private static final String STATE_PREFIX = MultiAgentGraphAgent.K_SUBTASK_PREFIX;
    private static final String NODE_0 = MultiAgentGraphAgent.SUBTASK_NODE_PREFIX + "0";

    /** 建监听器 + 已订阅收集器；返回 [listener, rows] */
    private Object[] listenerWithRows() {
        Sinks.Many<String> events = Sinks.many().unicast().onBackpressureBuffer();
        List<String> rows = new CopyOnWriteArrayList<>();
        BranchProgressListener listener = new BranchProgressListener(events,
                MultiAgentGraphAgent.SUBTASK_NODE_PREFIX, STATE_PREFIX);
        events.asFlux().subscribe(rows::add);
        return new Object[]{listener, rows};
    }

    private static Map<String, Object> stateWith(String key, String value) {
        return value == null ? Map.of() : Map.of(key, value);
    }

    @Test
    void after_failedOutcome_broadcastsSkippedNotDone() {
        Object[] pair = listenerWithRows();
        BranchProgressListener listener = (BranchProgressListener) pair[0];
        List<String> rows = (List<String>) pair[1];
        listener.before(NODE_0, stateWith(STATE_PREFIX + "0", "任务"), null, null);
        listener.recordOutcome(0, MultiAgentGraphAgent.FAILED_RESULT);
        listener.after(NODE_0, Map.of(), null, null); // 输入态快照：无 result 键（库语义）

        assertEquals(1, rows.size());
        assertTrue(ProgressLine.decode(rows.get(0)).detail().contains("执行失败，已跳过"),
                "失败占位应播报失败而非完成：" + rows.get(0));
    }

    @Test
    void after_timeoutAndBudgetOutcomes_broadcastActualOutcome() {
        Object[] pair = listenerWithRows();
        BranchProgressListener listener = (BranchProgressListener) pair[0];
        List<String> rows = (List<String>) pair[1];
        listener.before(NODE_0, stateWith(STATE_PREFIX + "0", "任务"), null, null);
        listener.recordOutcome(0, MultiAgentGraphAgent.TIMEOUT_SKIPPED_RESULT);
        listener.after(NODE_0, Map.of(), null, null);
        assertTrue(ProgressLine.decode(rows.get(0)).detail().contains("执行超时"), rows.get(0));

        listener.before(NODE_0.replace("0", "1"), stateWith(STATE_PREFIX + "1", "任务"), null, null);
        listener.recordOutcome(1, MultiAgentGraphAgent.SKIPPED_RESULT);
        listener.after(NODE_0.replace("0", "1"), Map.of(), null, null);
        assertTrue(ProgressLine.decode(rows.get(1)).detail().contains("预算超限"), rows.get(1));
    }

    @Test
    void after_noRegistration_stateFallbackStillClassifies() {
        Object[] pair = listenerWithRows();
        BranchProgressListener listener = (BranchProgressListener) pair[0];
        List<String> rows = (List<String>) pair[1];
        listener.before(NODE_0, stateWith(STATE_PREFIX + "0", "任务"), null, null);
        // 未登记（模拟未来库版本把合并后状态传入钩子）：state 含失败占位 → 仍按失败播报
        listener.after(NODE_0, stateWith(MultiAgentGraphAgent.K_RESULT_PREFIX + "0",
                MultiAgentGraphAgent.FAILED_RESULT), null, null);
        assertTrue(ProgressLine.decode(rows.get(0)).detail().contains("执行失败，已跳过"), rows.get(0));
    }

    @Test
    void after_successOrMissingResult_broadcastsDone() {
        Object[] pair = listenerWithRows();
        BranchProgressListener listener = (BranchProgressListener) pair[0];
        List<String> rows = (List<String>) pair[1];
        // 成功：登记真实结果（非占位）→ 完成
        listener.before(NODE_0, stateWith(STATE_PREFIX + "0", "任务"), null, null);
        listener.recordOutcome(0, "专家的真实回答");
        listener.after(NODE_0, Map.of(), null, null);
        assertEquals("第 1 个子任务完成", ProgressLine.decode(rows.get(0)).detail());
        // 无登记且 state 无 result（短路兜底路径）→ 完成兜底
        listener.before(NODE_0, stateWith(STATE_PREFIX + "0", "任务"), null, null);
        listener.after(NODE_0, Map.of(), null, null);
        assertEquals("第 1 个子任务完成", ProgressLine.decode(rows.get(1)).detail());
    }

    @Test
    void after_unscheduledSlot_silent() {
        Object[] pair = listenerWithRows();
        BranchProgressListener listener = (BranchProgressListener) pair[0];
        List<String> rows = (List<String>) pair[1];
        // before 未登记（lead 未布置该槽位，task 为空）→ after 静默
        listener.before(NODE_0, Map.of(), null, null);
        listener.after(NODE_0, Map.of(), null, null);
        assertEquals(0, rows.size(), "未布置槽位不应播报");
    }
}
