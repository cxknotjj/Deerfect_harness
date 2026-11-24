package com.dark.javaHarness.plugin;

/**
 * 插件扩展点类型：一种能力只允许一个启用实现（多实现启动失败，避免静默取第一个）。
 *
 * <p>首期只有 {@link #MEMORY}（长期记忆）；新增扩展点时在此登记并补对应接口。
 */
public enum PluginCapability {
    MEMORY
}
