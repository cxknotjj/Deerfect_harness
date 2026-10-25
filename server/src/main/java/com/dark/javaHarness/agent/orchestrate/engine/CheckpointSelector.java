package com.dark.javaHarness.agent.orchestrate.engine;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 按 DONE > SUBTASKS_DONE > LEAD_DONE 选择续跑点。
 */
public final class CheckpointSelector {

    private CheckpointSelector() {
    }

    public static ResumePlan select(List<OrchestrationCheckpoint> checkpoints) {
        if (checkpoints == null || checkpoints.isEmpty()) {
            throw new IllegalStateException("无可续跑的编排：没有检查点记录");
        }
        return Optional.of(checkpoints)
                .flatMap(list -> list.stream().max(Comparator
                        .comparingInt((OrchestrationCheckpoint c) -> c.stage().ordinal())
                        .thenComparing(OrchestrationCheckpoint::createdAt)))
                .map(c -> new ResumePlan(c, c.stage()))
                .orElseThrow();
    }

    public record ResumePlan(OrchestrationCheckpoint checkpoint, OrchestrationStage stage) {
    }
}
