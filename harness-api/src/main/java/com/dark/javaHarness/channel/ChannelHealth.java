package com.dark.javaHarness.channel;

/**
 * 渠道健康端口：由 channel 模块实现，core 只读消费。
 *
 * <p>放在 harness-api 是为了保持垂直包隔离——server 编译期不可见 channel 实现，
 * 只能经该接口拿到渠道状态。snapshot() 会被监控页轮询，实现需轻量、可重复调用，
 * detail 不得包含 token 等密钥信息。
 */
public interface ChannelHealth {

    /** 渠道展示名（如「NapCat / QQ」）。 */
    String channelName();

    /** 当前状态快照：enabled=配置开关，available=当前可达/可服务。 */
    Snapshot snapshot();

    record Snapshot(boolean enabled, boolean available, String detail) {
    }
}
