package com.dark.javaHarness.plugin;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 插件注册表：收集全部启用的插件，按扩展点提供唯一活跃实现。
 *
 * <p>同一扩展点存在多个启用实现时启动失败——静默取第一个会让配置错误变成运行期怪行为。
 * 未启用的插件不进入活跃表；插件自身的配置校验由实现类在构造期完成。
 */
@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    /** 扩展点 → 活跃插件（启动后只读） */
    private final Map<PluginCapability, Plugin> active;

    public PluginRegistry(List<Plugin> plugins) {
        Map<PluginCapability, Plugin> resolved = new EnumMap<>(PluginCapability.class);
        for (Plugin plugin : plugins) {
            if (!plugin.enabled()) {
                log.info("[plugin] 跳过未启用插件 {} ({})", plugin.name(), plugin.capability());
                continue;
            }
            Plugin previous = resolved.putIfAbsent(plugin.capability(), plugin);
            if (previous != null) {
                throw new IllegalStateException("扩展点 " + plugin.capability()
                        + " 存在多个启用插件: " + previous.name() + " / " + plugin.name());
            }
            log.info("[plugin] 已加载插件 {} ({}, enabled=true)", plugin.name(), plugin.capability());
        }
        this.active = Map.copyOf(resolved);
    }

    /** 按扩展点取唯一启用插件，并做类型校验（类型不匹配视为未配置） */
    public <T extends Plugin> Optional<T> active(PluginCapability capability, Class<T> type) {
        Plugin plugin = active.get(capability);
        return type.isInstance(plugin) ? Optional.of(type.cast(plugin)) : Optional.empty();
    }
}
