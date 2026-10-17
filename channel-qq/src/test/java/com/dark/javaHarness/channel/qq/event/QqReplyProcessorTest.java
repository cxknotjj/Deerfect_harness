package com.dark.javaHarness.channel.qq.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** QqReplyProcessor：剥离 [[voice]] 标记并声明 voice 标志；无标记时原样、标志为空。 */
class QqReplyProcessorTest {

    private final QqReplyProcessor processor = new QqReplyProcessor();

    @Test
    void stripsVoiceMarker_andDeclaresFlag() {
        var r = processor.process("[[voice]]你好呀");
        assertEquals("你好呀", r.text());
        assertTrue(r.flags().contains("voice"));
    }

    @Test
    void noMarker_passthrough_noFlags() {
        var r = processor.process("这是普通回复");
        assertEquals("这是普通回复", r.text());
        assertTrue(r.flags().isEmpty());
    }
}
