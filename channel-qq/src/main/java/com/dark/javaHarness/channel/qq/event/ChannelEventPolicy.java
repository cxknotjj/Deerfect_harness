package com.dark.javaHarness.channel.qq.event;

import com.dark.javaHarness.channel.qq.client.NapCatProperties;
import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 程序层事件准入策略（Fast Path）：把来源/群/用户/触发方式收敛为规则表，
 * 输出 响应（RESPOND）/ 候选（CANDIDATE）/ 忽略（IGNORE），与语义层（Slow Path）解耦。
 *
 * <p>规则按配置顺序首条匹配；`napcat.event-policy.rules` 为空时按既有
 * privateAllowUsers + groupTrigger 语义兜底，默认行为与改造前一致。
 * 候选事件交由语义层判断（napcat.semantic-trigger.enabled，默认关闭）。
 */
final class ChannelEventPolicy {

    enum Decision {
        RESPOND, CANDIDATE, IGNORE
    }

    /** 准入结果：decision + 供后续处理使用的正文（IGNORE 时为 null） */
    record Evaluation(Decision decision, String text) {

        static Evaluation ignore() {
            return new Evaluation(Decision.IGNORE, null);
        }
    }

    private final NapCatProperties props;
    private final EventTextParser textParser;
    private final Set<Long> privateAllowUsers;

    ChannelEventPolicy(NapCatProperties props, EventTextParser textParser) {
        this.props = props;
        this.textParser = textParser;
        this.privateAllowUsers = parseAllowUsers(props.getPrivateAllowUsers());
    }

    /** 判定一条入站事件是否进入聊天流程，以及使用哪段正文 */
    Evaluation evaluate(OneBotEvent event) {
        boolean group = "group".equals(event.messageType());
        String raw = textParser.extractText(event);
        if (raw == null || raw.isBlank()) {
            return Evaluation.ignore();
        }
        boolean atMe = group && textParser.atSelf(event, props.getSelfId());
        boolean replyMe = group && props.getGroupTrigger().isReplyToBot() && textParser.replyToSelf(event);

        for (NapCatProperties.EventPolicy.Rule rule : props.getEventPolicy().getRules()) {
            Decision decision = decisionOf(rule.getDecision());
            if (decision == null || !matches(rule, group, event, atMe, replyMe, raw)) {
                continue;
            }
            return new Evaluation(decision, textFor(rule.getTrigger(), raw));
        }
        return legacyDefault(group, event, atMe, replyMe, raw);
    }

    /** 规则未命中时的既有语义：私聊白名单 + 群聊 at/prefix/all 触发 */
    private Evaluation legacyDefault(boolean group, OneBotEvent event, boolean atMe,
                                     boolean replyMe, String raw) {
        if (!group) {
            if (!privateAllowUsers.isEmpty() && !privateAllowUsers.contains(event.userId())) {
                return Evaluation.ignore();
            }
            return new Evaluation(Decision.RESPOND, raw);
        }
        String mode = props.getGroupTrigger().getMode();
        switch (mode == null ? "at" : mode) {
            case "at" -> {
                if (!atMe && !replyMe) {
                    return Evaluation.ignore();
                }
                return new Evaluation(Decision.RESPOND, raw);
            }
            case "prefix" -> {
                if (!textParser.prefixMatched(raw)) {
                    return Evaluation.ignore();
                }
                String stripped = textParser.stripPrefix(raw);
                return stripped == null ? Evaluation.ignore() : new Evaluation(Decision.RESPOND, stripped);
            }
            case "all" -> {
                return new Evaluation(Decision.RESPOND, raw);
            }
            default -> {
                return Evaluation.ignore();
            }
        }
    }

    /** 规则匹配：留空字段不限制；trigger 为 any 时要求正文非空（已在上游校验） */
    private boolean matches(NapCatProperties.EventPolicy.Rule rule, boolean group, OneBotEvent event,
                            boolean atMe, boolean replyMe, String raw) {
        String source = trimLower(rule.getSource());
        if (!source.isEmpty() && !"any".equals(source)) {
            if ("private".equals(source) && group) {
                return false;
            }
            if ("group".equals(source) && !group) {
                return false;
            }
            if (!"private".equals(source) && !"group".equals(source)) {
                return false;
            }
        }
        if (!isBlank(rule.getGroups()) && (!group || !containsId(rule.getGroups(), event.groupId()))) {
            return false;
        }
        if (!isBlank(rule.getUsers()) && !containsId(rule.getUsers(), event.userId())) {
            return false;
        }
        String trigger = trimLower(rule.getTrigger());
        return switch (trigger) {
            case "", "any", "all" -> true;
            case "at" -> atMe;
            case "reply" -> replyMe;
            case "prefix" -> textParser.prefixMatched(raw);
            default -> false;
        };
    }

    /** 规则命中后的正文：prefix 规则剥离前缀，其余用原文 */
    private String textFor(String trigger, String raw) {
        return "prefix".equalsIgnoreCase(trigger) ? textParser.stripPrefix(raw) : raw;
    }

    private static Decision decisionOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "respond" -> {
                return Decision.RESPOND;
            }
            case "candidate" -> {
                return Decision.CANDIDATE;
            }
            case "ignore" -> {
                return Decision.IGNORE;
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean containsId(String csv, Long id) {
        if (id == null) {
            return false;
        }
        String needle = String.valueOf(id);
        for (String token : csv.split(",")) {
            if (needle.equals(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String trimLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 私聊白名单 CSV → Set&lt;Long&gt;（空串/空白 = 不限制；非法项跳过） */
    private static Set<Long> parseAllowUsers(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (String token : csv.split(",")) {
            try {
                ids.add(Long.parseLong(token.trim()));
            } catch (NumberFormatException ignored) {
                // 非法白名单项跳过（与既有解析语义一致）
            }
        }
        return ids;
    }
}
