package com.dark.javaHarness.agent.orchestrate.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dark.javaHarness.agent.orchestrate.engine.OrchestrationCheckpoint;
import com.dark.javaHarness.agent.orchestrate.engine.OrchestrationStage;
import com.dark.javaHarness.mapper.OrchestrationCheckpointMapper;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 编排检查点存储：替代 graph-core 的 MysqlSaver。
 *
 * <p>阶段一仍用 Jackson 2 序列化，Spring AI 2 迁移时随任务 11 切到 Jackson 3。
 */
@Component
@RequiredArgsConstructor
public class OrchestrationCheckpointStore {

    private final OrchestrationCheckpointMapper mapper;
    private final ObjectMapper objectMapper;

    public void save(String goalId, OrchestrationStage stage, Map<String, Object> state) {
        OrchestrationCheckpointEntity entity = new OrchestrationCheckpointEntity();
        entity.setGoalId(goalId);
        entity.setCheckpointId(UUID.randomUUID().toString());
        entity.setStage(stage.name());
        entity.setStateJson(writeJson(state));
        entity.setCreatedAt(LocalDateTime.now());
        mapper.insert(entity);
    }

    public List<OrchestrationCheckpoint> list(String goalId) {
        return mapper.selectList(new LambdaQueryWrapper<OrchestrationCheckpointEntity>()
                        .eq(OrchestrationCheckpointEntity::getGoalId, goalId)
                        .orderByAsc(OrchestrationCheckpointEntity::getCreatedAt))
                .stream()
                .map(this::toCheckpoint)
                .toList();
    }

    public Optional<OrchestrationCheckpoint> latest(String goalId) {
        return list(goalId).stream().reduce((a, b) -> b);
    }

    private String writeJson(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new IllegalStateException("检查点状态序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private OrchestrationCheckpoint toCheckpoint(OrchestrationCheckpointEntity entity) {
        try {
            Map<String, Object> state = objectMapper.readValue(entity.getStateJson(), Map.class);
            return new OrchestrationCheckpoint(
                    entity.getGoalId(),
                    entity.getCheckpointId(),
                    OrchestrationStage.valueOf(entity.getStage()),
                    state,
                    entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            throw new IllegalStateException("检查点状态反序列化失败: " + entity.getCheckpointId(), e);
        }
    }
}
