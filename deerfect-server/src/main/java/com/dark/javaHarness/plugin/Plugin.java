package com.dark.javaHarness.plugin;

/**
 * 插件契约：一个可独立启停的外部能力单元。
 *
 * <p>实现是普通 Spring Bean；由 {@link PluginRegistry} 在启动时统一收集与校验。
 * 插件不得依赖 {@code service.impl} / {@code agent} / {@code graph}，避免把能力反向耦合进对话主流程。
 */
public interface Plugin {

    /** 唯一标识（如 mem0），用于日志与配置定位 */
    String name();

    /** 扩展点类型 */
    PluginCapability capability();

    /** 是否启用（来自配置，默认 false；启用后配置非法应在启动期失败） */
    boolean enabled();
}
