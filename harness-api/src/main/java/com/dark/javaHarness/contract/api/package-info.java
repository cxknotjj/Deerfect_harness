/**
 * 调用型端口：由 server 实现，channel-* / cli / web 调用。
 *
 * <p>方向是「消费者调用 core」。这里的类型会直接出现在调用方代码里，签名变更属于破坏性变更，
 * 必须同步 {@code harness-api/README.md} 与 {@code PortSignatureGuardTest} 的快照。
 *
 * <p>「渠道实现、core 调用」的 SPI 不在这里，见 {@link com.dark.javaHarness.contract.spi}；
 * 端口签名用到的数据类型见 {@link com.dark.javaHarness.contract.model}。
 */
package com.dark.javaHarness.contract.api;
