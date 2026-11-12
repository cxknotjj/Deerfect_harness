package com.dark.javaHarness.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CheckpointSelectorTest {

    private static OrchestrationCheckpoint cp(String id, OrchestrationStage stage) {
        return new OrchestrationCheckpoint("g1", id, stage, Map.of("final", "答案"), Instant.now());
    }

    @Test
    void doneReplaysFinal() {
        CheckpointSelector.ResumePlan plan = CheckpointSelector.select(
                List.of(cp("1", OrchestrationStage.LEAD_DONE), cp("2", OrchestrationStage.DONE)));
        assertEquals(OrchestrationStage.DONE, plan.stage());
        assertEquals("2", plan.checkpoint().checkpointId());
    }

    @Test
    void subtasksDoneRunsAggregateOnly() {
        CheckpointSelector.ResumePlan plan = CheckpointSelector.select(
                List.of(cp("1", OrchestrationStage.LEAD_DONE), cp("2", OrchestrationStage.SUBTASKS_DONE)));
        assertEquals(OrchestrationStage.SUBTASKS_DONE, plan.stage());
    }

    @Test
    void leadDoneRunsSubtasksThenAggregate() {
        CheckpointSelector.ResumePlan plan = CheckpointSelector.select(
                List.of(cp("1", OrchestrationStage.LEAD_DONE)));
        assertEquals(OrchestrationStage.LEAD_DONE, plan.stage());
    }

    @Test
    void emptyCheckpointsFailFast() {
        assertThrows(IllegalStateException.class, () -> CheckpointSelector.select(List.of()));
    }
}
