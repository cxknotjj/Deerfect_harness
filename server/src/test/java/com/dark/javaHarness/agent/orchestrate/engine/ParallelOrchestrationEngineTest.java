package com.dark.javaHarness.agent.orchestrate.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.orchestrate.OrchestrationBudget;
import com.dark.javaHarness.agent.orchestrate.OrchestrationNodes;
import com.dark.javaHarness.agent.orchestrate.persistence.OrchestrationCheckpointStore;
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
}
