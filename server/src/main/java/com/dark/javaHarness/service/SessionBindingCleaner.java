package com.dark.javaHarness.service;

/**
 * 外部渠道会话绑定清理端口：删除 harness 会话时通知各渠道清理自己的绑定行。
 *
 * <p>绑定数据（如 QQ OneBot 的 onebot_session_binding 表）归渠道所有，
 * 实现位于 channel/qq 包内、随 {@code napcat.enabled} 条件装配；core 经本
 * 接口反向解耦，禁止 import channel 包（渠道隔离硬约束）。实现可选：
 * SessionServiceImpl 经 ObjectProvider 注入，渠道未启用时无实现、静默跳过。
 */
public interface SessionBindingCleaner {

    /** 删除指定 harness 会话（session 表自增主键）的全部渠道绑定行；无绑定静默 */
    void deleteBySessionId(long sessionId);
}
