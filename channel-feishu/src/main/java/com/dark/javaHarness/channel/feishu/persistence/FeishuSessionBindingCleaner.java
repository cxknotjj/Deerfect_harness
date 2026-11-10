package com.dark.javaHarness.channel.feishu.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.contract.spi.SessionBindingCleaner;

/**
 * {@link SessionBindingCleaner} 的飞书实现：按 harness 会话删绑定行。
 *
 * <p>没有这个实现时，Web 端删掉会话后飞书绑定仍指向已删的 sessionId。核心的写回有
 * 「防复活守卫」（{@code appendBatch} 发现会话不存在就跳过），所以不会报错——
 * 表现是每轮都拿空上下文回答，而且绑定永远不会重建，用户只会觉得「它突然失忆了」。
 *
 * <p>由 {@code FeishuWsConfig} 以 @Bean 装配（继承长连接开关）；未启用时无实现，
 * SessionServiceImpl 侧静默跳过。
 */
public class FeishuSessionBindingCleaner implements SessionBindingCleaner {

    private final FeishuSessionBindingMapper bindingMapper;

    public FeishuSessionBindingCleaner(FeishuSessionBindingMapper bindingMapper) {
        this.bindingMapper = bindingMapper;
    }

    @Override
    public void deleteBySessionId(long sessionId) {
        QueryWrapper<FeishuSessionBinding> query = new QueryWrapper<>();
        query.eq("session_id", sessionId);
        bindingMapper.delete(query);
    }
}
