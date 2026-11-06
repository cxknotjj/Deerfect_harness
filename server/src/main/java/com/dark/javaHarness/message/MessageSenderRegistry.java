package com.dark.javaHarness.message;

import com.dark.javaHarness.service.MessageSender;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 主动推送的渠道注册表：把 {@code channel:target} 形式的地址路由到对应渠道实现。
 *
 * <p>地址格式（存于 agent_schedule.notify_channel）：{@code qq:123456} —— 冒号前是渠道标识，
 * 之后原样交给实现（QQ 是 userId，将来企微可能是 userid 或群 id）。
 *
 * <p>为什么不用事件：Goal 完成点在 AgentServiceImpl 有三处（直跑、流式成功、流式失败），
 * 发事件容易漏发或重发；改为「任务侧按 last_goal_id 轮询 goal 状态 + notified_at 幂等标记」，
 * 少一处对执行链的侵入，且失败天然下一轮重试。
 */
@Component
public class MessageSenderRegistry {

    private static final Logger log = LoggerFactory.getLogger(MessageSenderRegistry.class);

    private final Map<String, MessageSender> byChannel;

    public MessageSenderRegistry(List<MessageSender> senders) {
        Map<String, MessageSender> map = new LinkedHashMap<>();
        for (MessageSender sender : senders) {
            MessageSender previous = map.putIfAbsent(sender.channel(), sender);
            if (previous != null) {
                // 同渠道多个实现：先注册者优先，暴露出来避免「发到了另一个实现」的静默错乱
                log.warn("[message] 渠道 {} 存在多个实现，忽略 {}", sender.channel(), sender.getClass().getName());
            }
        }
        this.byChannel = Map.copyOf(map);
        log.info("[message] 主动推送渠道: {}", byChannel.keySet());
    }

    /**
     * 按 {@code channel:target} 发送；地址非法、渠道未注册、实现抛异常都返回 false 并记日志。
     *
     * @return 是否送达
     */
    public boolean send(String channelRef, String text) {
        if (channelRef == null || channelRef.isBlank() || text == null || text.isBlank()) {
            return false;
        }
        int separator = channelRef.indexOf(':');
        if (separator <= 0 || separator == channelRef.length() - 1) {
            log.warn("[message] 推送地址非法（应为 channel:target）: {}", channelRef);
            return false;
        }
        String channel = channelRef.substring(0, separator).trim();
        String target = channelRef.substring(separator + 1).trim();
        MessageSender sender = byChannel.get(channel);
        if (sender == null) {
            log.warn("[message] 渠道未注册，推送失败 channel={}（已注册: {}）", channel, byChannel.keySet());
            return false;
        }
        try {
            boolean ok = sender.send(target, text);
            if (!ok) {
                log.warn("[message] 推送未送达 channel={} target={}", channel, target);
            }
            return ok;
        } catch (Exception e) {
            // 实现方按约定不抛，这里兜住第三方实现违约，避免把推送失败升级成任务失败
            log.warn("[message] 推送异常 channel={} target={}: {}", channel, target, e.toString());
            return false;
        }
    }

    /** 已注册的渠道标识（排障与监控用） */
    public Set<String> channels() {
        return byChannel.keySet();
    }
}
