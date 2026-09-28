package com.dark.javaHarness.channel.qq;

import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import java.util.List;

/**
 * 入站事件文本提取与触发判定（自 {@link OneBotEventServiceImpl} 拆出，超长类拆分 2026-09-25）：
 * 私聊全文提取、群聊触发判定（at/prefix/all）、@ 自判据，仅依赖 NapCatProperties。
 */
final class EventTextParser {

    private final NapCatProperties props;

    EventTextParser(NapCatProperties props) {
        this.props = props;
    }

    /** 私聊全文响应；正文取 message 数组 text 段，数组缺失兜底 raw_message */
    String extractPrivateText(OneBotEvent event) {
        String joined = joinTextSegments(event.message());
        if (!joined.isBlank()) {
            return joined.trim();
        }
        return event.rawMessage() == null ? null : event.rawMessage().trim();
    }

    /**
     * 群聊触发判断：at=仅@机器人（message 数组 at 段为准，raw_message CQ 码兜底）；
     * prefix=命令前缀；all=全部响应。返回 null 表示不触发。
     */
    String extractGroupText(OneBotEvent event) {
        String mode = props.getGroupTrigger().getMode();
        String joined = joinTextSegments(event.message());
        String selfId = props.getSelfId();
        switch (mode == null ? "at" : mode) {
            case "at" -> {
                if (!atSelf(event, selfId)) {
                    return null;
                }
                return joined.isBlank() ? null : joined.trim();
            }
            case "prefix" -> {
                String base = joined.isBlank() ? nullToEmpty(event.rawMessage()).trim() : joined.trim();
                String prefix = nullToEmpty(props.getGroupTrigger().getPrefix());
                if (base.isBlank()) {
                    return null;
                }
                if (!prefix.isBlank()) {
                    if (!base.startsWith(prefix)) {
                        return null;
                    }
                    base = base.substring(prefix.length()).trim();
                }
                return base.isBlank() ? null : base;
            }
            default -> {
                // all：全量响应
                String base = joined.isBlank() ? nullToEmpty(event.rawMessage()).trim() : joined.trim();
                return base.isBlank() ? null : base;
            }
        }
    }

    /** @ 触发判据：message 数组 at 段 data.qq == self-id；数组缺失时兜底 raw_message CQ 码 */
    boolean atSelf(OneBotEvent event, String selfId) {
        if (selfId == null || selfId.isBlank()) {
            return false;
        }
        if (event.message() != null) {
            return event.message().stream().anyMatch(s -> "at".equals(s.type()) && selfId.equals(s.qq()));
        }
        return nullToEmpty(event.rawMessage()).contains("[CQ:at,qq=" + selfId + "]");
    }

    private String joinTextSegments(List<MessageSegment> segments) {
        if (segments == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MessageSegment segment : segments) {
            if ("text".equals(segment.type())) {
                sb.append(segment.text());
            }
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
