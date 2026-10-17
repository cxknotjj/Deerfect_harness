package com.dark.javaHarness.channel.qq.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** CommandParser：识别 "/name arg"，非指令返回 null。 */
class CommandParserTest {

    @Test
    void parseWithArg() {
        CommandParser.Parsed p = CommandParser.parse("/agent writer");
        assertEquals("agent", p.name());
        assertEquals("writer", p.arg());
    }

    @Test
    void parseBareCommand() {
        CommandParser.Parsed p = CommandParser.parse("  /help  ");
        assertEquals("help", p.name());
        assertEquals("", p.arg());
    }

    @Test
    void parseCaseInsensitive() {
        assertEquals("reset", CommandParser.parse("/RESET").name());
    }

    @Test
    void nonCommandReturnsNull() {
        assertNull(CommandParser.parse("你好"));
        assertNull(CommandParser.parse("/"));
        assertNull(CommandParser.parse(null));
    }
}
