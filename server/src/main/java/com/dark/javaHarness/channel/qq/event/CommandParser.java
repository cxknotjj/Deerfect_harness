package com.dark.javaHarness.channel.qq.event;

import java.util.Locale;

/**
 * QQ 指令解析纯函数（无状态）：把 "/name arg" 解析为 (name, arg)。
 * 不以 "/" 开头、或 "/" 后为空视为非指令（返回 null，交由正常聊天处理）。
 */
final class CommandParser {

    private CommandParser() {
    }

    /** 解析结果：name 已小写化；arg 为去首尾空白后的剩余文本（无参为空串） */
    record Parsed(String name, String arg) {
    }

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
        String arg = sp < 0 ? "" : body.substring(sp + 1).trim();
        return new Parsed(name, arg);
    }
}
