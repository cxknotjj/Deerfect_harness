package com.dark.javaHarness.domain;

/**
 * 一条待投递任务（outbox 的领域视图）：调度器只关心这些字段，不接触 {@code DeliveryOutboxEntity}。
 *
 * @param id            outbox 行主键
 * @param source        来源（schedule / alert / manual），告警抑制与排障用
 * @param refId         业务指针（schedule.id / goal.id）
 * @param channelChain  降级链原文
 * @param chainIndex    当前尝试到第几跳（0 起）
 * @param content       正文
 * @param attempts      总尝试次数（**已包含本次领取**，审计用）
 * @param hopAttempts   当前跳的尝试次数（**已包含本次领取**，决定退避与链推进）
 * @param chainLength   链上通道总数（判断「还有没有下一跳」）
 */
public record DeliveryTask(long id, String source, String refId, String channelChain,
                           int chainIndex, String content, int attempts, int hopAttempts,
                           int chainLength) {

    /** 是否还有下一跳可用 */
    public boolean hasNextHop() {
        return chainIndex + 1 < chainLength;
    }
}
