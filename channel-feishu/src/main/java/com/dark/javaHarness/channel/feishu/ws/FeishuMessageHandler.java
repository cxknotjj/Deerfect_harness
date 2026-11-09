package com.dark.javaHarness.channel.feishu.ws;

/**
 * 入站消息处理器：在渠道自己的线程池上执行，可以慢（调模型、发回复）。
 *
 * <p>与 {@link FeishuInboundService} 的分工是这个渠道的「3 秒约束」所在：
 * 入站服务只做判定与入队、必须立刻返回；耗时的事全在这里。
 */
@FunctionalInterface
public interface FeishuMessageHandler {

    void handle(FeishuInboundMessage message);
}
