package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 模型路由：Agent 唯一绑定的稳定别名。
 *
 * <p>code 为稳定标识（main / fast / judge / multi），路由成员见
 * {@link LlmRouteMemberEntity}。
 */
@Data
@TableName("llm_route")
public class LlmRouteEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String description;

    /** 当前仅 priority */
    private String strategy;

    /** 1-启用 0-禁用 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
