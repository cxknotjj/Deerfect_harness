package com.dark.javaHarness.agent.orchestrate.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 编排检查点实体：对应 V41 的 orchestration_checkpoint 表。
 */
@Data
@TableName("orchestration_checkpoint")
public class OrchestrationCheckpointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String goalId;

    private String checkpointId;

    private String stage;

    private String stateJson;

    private LocalDateTime createdAt;
}
