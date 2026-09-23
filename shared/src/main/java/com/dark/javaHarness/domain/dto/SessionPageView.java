package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 会话分页查询响应（GET /api/harness/sessions）。
 * 携带分页元数据（page/size/total/pages）与当前页的会话列表。
 * 单条会话由嵌套的 {@link Item} 表示；实体/分页装配见 server 侧 HarnessController。
 */
public record SessionPageView(
        long page,
        long size,
        long total,
        long pages,
        List<Item> sessions) {

    /**
     * 单条会话响应项。
     * lastActiveAt 为最近活跃时刻（epoch 毫秒，前端侧栏相对时间用；旧数据/异常为 null）。
     */
    public record Item(String id, String name, String creator, String lastQuestion, Long lastActiveAt) {
    }
}
