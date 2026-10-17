package com.dark.javaHarness.channel.qq.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.service.SessionBindingCleaner;

/**
 * {@link SessionBindingCleaner} 的 QQ 渠道实现：按 harness 会话删绑定行。
 * 由 NapCatChannelConfig 以 @Bean 装配（继承 napcat.enabled 条件），
 * 渠道未启用时无实现，SessionServiceImpl 侧静默跳过。
 */
public class OneBotSessionBindingCleaner implements SessionBindingCleaner {

    private final OneBotSessionBindingMapper bindingMapper;

    public OneBotSessionBindingCleaner(OneBotSessionBindingMapper bindingMapper) {
        this.bindingMapper = bindingMapper;
    }

    @Override
    public void deleteBySessionId(long sessionId) {
        QueryWrapper<OneBotSessionBinding> qw = new QueryWrapper<>();
        qw.eq("session_id", sessionId);
        bindingMapper.delete(qw);
    }
}
