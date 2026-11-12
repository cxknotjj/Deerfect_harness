package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 路由成员：一条路由下的有序候选，构成失败切换链。
 *
 * <p>主键是 {@code (routeId, modelId)} 复合键；同一路由内 {@code priority} 唯一，
 * 越小越优先。
 */
@Data
@TableName("llm_route_member")
public class LlmRouteMemberEntity {

    private Long routeId;

    private Long modelId;

    /** 越小越优先，同一路由内唯一 */
    private Integer priority;

    /** 1-启用 0-禁用 */
    private Integer enabled;
}
