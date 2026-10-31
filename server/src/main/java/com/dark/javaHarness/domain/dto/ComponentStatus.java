package com.dark.javaHarness.domain.dto;

/**
 * 组件状态：监控页展示外部依赖/工具通道是否启用且当前可用。
 *
 * <p>enabled=配置层面是否启用；available=当前是否可达/已就绪；detail 为展示文案，
 * 不得包含 token、url query 等密钥信息。
 */
public record ComponentStatus(String name, boolean enabled, boolean available, String detail) {
}
