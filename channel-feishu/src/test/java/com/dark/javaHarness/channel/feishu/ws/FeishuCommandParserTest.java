package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 指令解析：只认白名单里的名字，其余一律回落成普通消息 */
class FeishuCommandParserTest {

    @Test
    void plainText_isNotACommand() {
        assertNull(FeishuCommandParser.parse("你好"));
        assertNull(FeishuCommandParser.parse(null));
    }

    @Test
    void slashOnly_isNotACommand() {
        assertNull(FeishuCommandParser.parse("/"));
        assertNull(FeishuCommandParser.parse("   /   "));
    }

    @Test
    void knownCommand_parsesNameAndEmptyArg() {
        FeishuCommandParser.Parsed parsed = FeishuCommandParser.parse("/help");

        assertEquals("help", parsed.name());
        assertEquals("", parsed.arg());
    }

    @Test
    void commandName_isLowercasedAndArgKept() {
        FeishuCommandParser.Parsed parsed = FeishuCommandParser.parse("  /HISTORY 3  ");

        assertEquals("history", parsed.name());
        assertEquals("3", parsed.arg());
    }

    @Test
    void argWithSpaces_keepsRest() {
        FeishuCommandParser.Parsed parsed = FeishuCommandParser.parse("/agent 多 空格");

        assertEquals("agent", parsed.name());
        assertEquals("多 空格", parsed.arg());
    }

    @Test
    void status_isKnownCommand() {
        assertEquals("status", FeishuCommandParser.parse("/status").name());
    }

    @Test
    void recall_isKnownCommand() {
        assertEquals("recall", FeishuCommandParser.parse("/recall").name());
    }

    /** 不认识的斜杠开头消息交回聊天：用户可能只是发了条以 / 开头的普通文本 */
    @Test
    void unknownCommand_isNotACommand() {
        assertNull(FeishuCommandParser.parse("/roll"));
        assertNull(FeishuCommandParser.parse("/tmp 是什么"));
    }
}
