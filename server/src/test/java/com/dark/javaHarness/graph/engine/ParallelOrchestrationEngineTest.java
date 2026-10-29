package com.dark.javaHarness.graph.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.graph.MultiAgentGraphAgent;
import com.dark.javaHarness.graph.OrchestrationBudget;
import com.dark.javaHarness.graph.OrchestrationNodes;
import com.dark.javaHarness.state.OrchestrationStage;
import com.dark.javaHarness.state.persistence.OrchestrationCheckpointStore;
import com.dark.javaHarness.domain.Goal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParallelOrchestrationEngineTest {

    @Test
    void executeRunsLeadSubtasksAggregateInOrder() {
        OrchestrationNodes nodes = mock(OrchestrationNodes.class);
        when(nodes.lead(any(OrchestrationState.class), any(), any()))
                .thenReturn(Map.of(
                        "subtaskCount", 1,
                        "subtask_0", "查天气",
                        "subtaskAgent_0", "researcher"));
        when(nodes.subtask(any(OrchestrationState.class), anyInt(), any(), any(), any()))
                .thenReturn(Map.of("result_0", "晴"));
        when(nodes.aggregate(any(OrchestrationState.class), any(), any(), any()))
                .thenReturn(Map.of("final", "今天晴"));

        OrchestrationCheckpointStore store = mock(OrchestrationCheckpointStore.class);
        ParallelOrchestrationEngine engine = new ParallelOrchestrationEngine(
                nodes, mock(OrchestrationBudget.class), store, 2);

        String answer = engine.execute(new Goal("g1", "今天天气", null, null, null));

        assertEquals("今天晴", answer);
        verify(store).save(eq("g1"), eq(OrchestrationStage.LEAD_DONE), anyMap());
        verify(store).save(eq("g1"), eq(OrchestrationStage.SUBTASKS_DONE), anyMap());
        verify(store).save(eq("g1"), eq(OrchestrationStage.DONE), anyMap());
    }

    @Test
    void execute_aggregateFails_degradeToSubtaskDigest() {
        // 简化 Graph 降级：聚合节点失败但子任务已有结果时，用结果汇总产出最终回答，不整体作废
        OrchestrationNodes nodes = mock(OrchestrationNodes.class);
        when(nodes.lead(any(OrchestrationState.class), any(), any()))
                .thenReturn(Map.of("subtaskCount", 2, "subtask_0", "A", "subtask_1", "B"));
        when(nodes.subtask(any(OrchestrationState.class), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> Map.of("result_" + inv.getArgument(1),
                        "结论" + inv.getArgument(1)));
        when(nodes.aggregate(any(OrchestrationState.class), any(), any(), any()))
                .thenThrow(new IllegalStateException("聚合调用失败"));

        ParallelOrchestrationEngine engine = new ParallelOrchestrationEngine(
                nodes, mock(OrchestrationBudget.class), null, 2);

        String answer = engine.execute(new Goal("g2", "两个问题", null, null, null));

        assertTrue(answer.contains("结论0"), "应包含第一个子任务结果");
        assertTrue(answer.contains("结论1"), "应包含第二个子任务结果");
    }

    @Test
    void execute_aggregateFailsWithoutUsableResults_rethrowsForAgenticFallback() {
        // 无可用子任务结果（全部失败占位）时不做简化降级，异常上抛交给上层 Agentic 兜底
        OrchestrationNodes nodes = mock(OrchestrationNodes.class);
        when(nodes.lead(any(OrchestrationState.class), any(), any()))
                .thenReturn(Map.of("subtaskCount", 1, "subtask_0", "A"));
        when(nodes.subtask(any(OrchestrationState.class), anyInt(), any(), any(), any()))
                .thenReturn(Map.of("result_0", MultiAgentGraphAgent.FAILED_RESULT));
        when(nodes.aggregate(any(OrchestrationState.class), any(), any(), any()))
                .thenThrow(new IllegalStateException("聚合调用失败"));

        ParallelOrchestrationEngine engine = new ParallelOrchestrationEngine(
                nodes, mock(OrchestrationBudget.class), null, 1);

        assertThrows(IllegalStateException.class,
                () -> engine.execute(new Goal("g3", "单问题", null, null, null)));
    }
}
