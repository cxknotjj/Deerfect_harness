package com.dark.javaHarness.channel;

/**
 * 渠道健康端口：由 channel 模块实现，core 只读消费。
 *
 * <p>放在 harness-api 是为了保持垂直包隔离——server 编译期不可见 channel 实现，
 * 只能经该接口拿到渠道状态。snapshot() 会被监控页轮询，实现需轻量、可重复调用，
 * detail 不得包含 token 等密钥信息。
 *
 * <p><b>为什么是三态而不是布尔</b>：渠道「拿不到结论」和「确认故障」必须分开。邮件渠道
 * 用最近一次投递当探针，7 天没投递时只能说「不知道」——把它当故障，会让探活告警
 * （`ChannelWatchdog`）每周发一条假警报，最后没人再看告警。
 */
public interface ChannelHealth {

    /** 渠道展示名（如「NapCat / QQ」）。 */
    String channelName();

    /**
     * 渠道标识（{@code qq} / {@code email} / {@code feishu}），与 {@link
     * com.dark.javaHarness.service.MessageSender#channel()} 取值一致。
     *
     * <p>唯一用途：渠道故障时，告警链要排除「本渠道」——不能用飞书去报「飞书挂了」。
     * 未覆盖此方法的实现仍能工作，只是告警不会排除本渠道（退化为「碰运气」）。
     */
    default String channelId() {
        return "";
    }

    /** 当前状态快照。 */
    Snapshot snapshot();

    /** 渠道状态四态：把「确认坏了」与「不知道」分开，两者的处置完全不同。 */
    enum Status {
        /** 已启用且可达 / 最近一次真实流量成功 */
        UP,
        /** 已启用但确认不可用：探活失败、最近一次投递失败 */
        DOWN,
        /** 已启用但拿不到可信结论：无近期流量、探活尚未执行。不参与探活告警 */
        UNKNOWN,
        /** 配置关闭（enabled=false），不参与探活告警 */
        DISABLED
    }

    /**
     * 状态快照。
     *
     * <p>{@code enabled} / {@code available} 是为监控页保留的布尔投影：前者等价于
     * 「不是 DISABLED」，后者等价于「UP」。新代码判状态请直接用 {@link #status()}——
     * 用 available=false 判断故障会把 UNKNOWN 也算进去。
     *
     * @param status 四态之一，null 按 UNKNOWN 处理
     * @param detail 给人看的说明；排障时这是第一手信息，但不是判定依据（不要按文案分支）
     */
    record Snapshot(Status status, String detail) {

        public Snapshot {
            status = status == null ? Status.UNKNOWN : status;
            detail = detail == null ? "" : detail;
        }

        /** 兼容旧实现的构造：enabled/available 二元组按「关 / 坏 / 好」映射 */
        public Snapshot(boolean enabled, boolean available, String detail) {
            this(!enabled ? Status.DISABLED : (available ? Status.UP : Status.DOWN), detail);
        }

        public boolean enabled() {
            return status != Status.DISABLED;
        }

        public boolean available() {
            return status == Status.UP;
        }
    }
}
