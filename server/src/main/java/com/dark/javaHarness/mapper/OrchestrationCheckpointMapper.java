package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.agent.orchestrate.persistence.OrchestrationCheckpointEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 编排检查点 Mapper：放在既有 @MapperScan 覆盖的 mapper 包下。
 */
@Mapper
public interface OrchestrationCheckpointMapper extends BaseMapper<OrchestrationCheckpointEntity> {
}
