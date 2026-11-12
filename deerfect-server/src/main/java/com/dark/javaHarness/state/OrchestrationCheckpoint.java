package com.dark.javaHarness.state;

import java.time.Instant;
import java.util.Map;

/**
 * 一次检查点快照。
 */
public record OrchestrationCheckpoint(String goalId,
                                      String checkpointId,
                                      OrchestrationStage stage,
                                      Map<String, Object> state,
                                      Instant createdAt) {
}
