/**
 * 实现型 SPI：由渠道模块实现，server 调用。
 *
 * <p>方向与 {@link com.dark.javaHarness.contract.api} 相反。实现分散在 channel-qq /
 * channel-email / channel-feishu，core 只依赖接口——渠道在 {@code server/pom.xml} 里是
 * runtime 依赖，compile 期不可见，这也是「新增渠道不改 core」的前提。
 *
 * <p>共同约定：<b>实现不得抛异常</b>，所有失败折成返回值（{@link
 * com.dark.javaHarness.contract.spi.MessageSender.DeliveryReceipt} 的 retryable、
 * {@link com.dark.javaHarness.contract.spi.ChannelHealth.Snapshot} 的四态）。抛出来的异常
 * 在 core 侧只会被记成一条日志，投递层与监控都拿不到可读原因。
 */
package com.dark.javaHarness.contract.spi;
