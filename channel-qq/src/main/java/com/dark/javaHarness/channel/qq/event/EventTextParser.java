package com.dark.javaHarness.channel.qq.event;

import com.dark.javaHarness.channel.qq.client.NapCatProperties;
import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import java.util.List;

/**
 * 入站事件文本提取与触发判定（自 {@link OneBotEventServiceImpl} 拆出，超长类拆分 2026-09-25）：
 * 私聊全文提取、群聊触发判定（at/prefix/all）、@ 自判据/回复自判据，仅依赖 NapCatProperties。
 */
final class EventTextParser {

    private final NapCatProperties props;
    private final SentMessageTracker sentTracker;

    EventTextParser(NapCatProperties props, SentMessageTracker sentTracker) {
        this.props = props;
        this.sentTracker = sentTracker;
    }

    /** 入站正文提取：message 数组 text 段优先，数组缺失兜底 raw_message；空正文返回 null */
    String extractText(OneBotEvent event) {
        String joined = joinTextSegments(event.message());
        String text = joined.isBlank() ? nullToEmpty(event.rawMessage()).trim() : joined.trim();
        return text.isBlank() ? null : text;
    }

    /** 命令前缀是否命中（前缀未配置视为命中，与既有 prefix 模式语义一致） */
    boolean prefixMatched(String text) {
        String prefix = nullToEmpty(props.getGroupTrigger().getPrefix());
        return prefix.isBlank() || (text != null && text.startsWith(prefix));
    }

    /** 剥离命令前缀（未配置前缀原样返回） */
    String stripPrefix(String text) {
        String prefix = nullToEmpty(props.getGroupTrigger().getPrefix());
        if (prefix.isBlank() || text == null) {
            return text;
        }
        String stripped = text.substring(prefix.length()).trim();
        return stripped.isBlank() ? null : stripped;
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

    /** 回复机器人判据：message 数组存在 reply 段且其引用的 message_id 命中我方已发记录 */
    boolean replyToSelf(OneBotEvent event) {
        if (event.message() == null) {
            return false;
        }
        return event.message().stream()
                .filter(s -> "reply".equals(s.type()))
                .map(MessageSegment::replyTargetId)
                .anyMatch(sentTracker::contains);
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
