package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.contract.model.ChatAttachment;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.UserMessage;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 多模态消息构造：图片以 ImageBlock 内联，而不是被降级成一句「用户发了张图」。
 *
 * <p>这条链路最容易静默失败——图片没进内容块，模型只会当纯文本回，接口不报任何错。
 */
class AgentScopeUserMessageTest {

    @Test
    void noAttachments_isPureTextMessage() {
        UserMessage message = AgentScopeGeneralAgent.userMessage("你好", List.of());

        assertEquals(1, message.getContent().size());
        assertEquals("你好", message.getTextContent());
        assertTrue(message.getContentBlocks(ImageBlock.class).isEmpty());
    }

    @Test
    void imageAttachment_becomesImageBlockWithBase64Source() {
        byte[] data = {1, 2, 3, 4};
        UserMessage message = AgentScopeGeneralAgent.userMessage("这是什么",
                List.of(new ChatAttachment("image/png", data)));

        assertEquals(2, message.getContent().size(), "一个文本块 + 一个图片块");
        List<ImageBlock> images = message.getContentBlocks(ImageBlock.class);
        assertEquals(1, images.size());

        Base64Source source = assertInstanceOf(Base64Source.class, images.get(0).getSource());
        assertEquals("image/png", source.getMediaType());
        assertEquals(Base64.getEncoder().encodeToString(data), source.getData());
        assertEquals("这是什么", message.getTextContent());
    }

    @Test
    void multipleImages_keepOrder() {
        UserMessage message = AgentScopeGeneralAgent.userMessage("比一比", List.of(
                new ChatAttachment("image/png", new byte[]{1}),
                new ChatAttachment("image/jpeg", new byte[]{2})));

        List<ImageBlock> images = message.getContentBlocks(ImageBlock.class);
        assertEquals(2, images.size());
        assertEquals("image/png", ((Base64Source) images.get(0).getSource()).getMediaType());
        assertEquals("image/jpeg", ((Base64Source) images.get(1).getSource()).getMediaType());
    }

    /** 非图片附件没有来源，塞进请求只会让模型侧报错，直接跳过 */
    @Test
    void nonImageAttachment_isSkipped() {
        UserMessage message = AgentScopeGeneralAgent.userMessage("看文件",
                List.of(new ChatAttachment("application/pdf", new byte[]{1})));

        assertEquals(1, message.getContent().size());
        assertTrue(message.getContentBlocks(ImageBlock.class).isEmpty());
    }
}
