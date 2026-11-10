package com.dark.javaHarness.channel.feishu.ws;

import java.util.Locale;
import java.util.Set;

/**
 * 飞书指令解析纯函数（无状态）：把 {@code /name arg} 解析为 (name, arg)。
 *
 * <p>与 QQ 的 {@code CommandParser} 是同一套语法，但刻意各留一份：渠道模块之间
 * 不能互相依赖（{@code channel-feishu} 看不见 {@code channel-qq}）。两边只有十来行，
 * 复制比抽公共模块便宜——抽出去就得为一个纯函数新开一个模块。
 *
 * <p>只认白名单里的指令名。不认识的 {@code /xxx} 返回 {@code null}，交回正常聊天：
 * 用户可能只是发了条以斜杠开头的普通消息，不该被指令层吞掉。
 */
final class FeishuCommandParser {

    /** 支持的命令名；新增命令时同步 {@code HELP_TEXT} 与 README */
    static final Set<String> NAMES = Set.of("new", "reset", "help", "history", "agent", "status");

    private FeishuCommandParser() {
    }

    /** 解析结果：name 已小写化；arg 为去首尾空白后的剩余文本（无参为空串） */
    record Parsed(String name, String arg) {
    }

    /** 不以 {@code /} 开头、{@code /} 后为空、或指令名不认识，都返回 null（按普通消息处理） */
    static Parsed parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (!t.startsWith("/")) {
            return null;
        }
        String body = t.substring(1).trim();
        if (body.isEmpty()) {
            return null;
        }
        int sp = body.indexOf(' ');
        String name = (sp < 0 ? body : body.substring(0, sp)).toLowerCase(Locale.ROOT);
        if (!NAMES.contains(name)) {
            return null;
        }
        String arg = sp < 0 ? "" : body.substring(sp + 1).trim();
        return new Parsed(name, arg);
    }
}
