package com.dark.javaHarness.channel.feishu.ws;

/**
 * 长连接传输层的抽象：把「生命周期策略」与「SDK 细节」分开。
 *
 * <p>分开的理由是可测：`FeishuWsClient` 只负责「何时启停、停机怎么收尾」，
 * 可以在单测里用假 transport 验证；真正的 SDK 建连逻辑在
 * {@link SdkFeishuWsTransport}，它需要网络，不适合进单测。
 */
public interface FeishuWsTransport {

    /** 建立连接（立即返回，收消息在实现自己的线程里） */
    void start();

    /** 断开连接；允许在未启动时调用 */
    void stop();
}
