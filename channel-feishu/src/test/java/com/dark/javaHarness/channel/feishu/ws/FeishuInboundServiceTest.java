package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 入站第一跳单测：单聊/群聊接受、@ 剥离、白名单、去重、消息类型解析。
 *
 * <p>这里验的是「3 秒内该不该处理」，不是模型回复；所以断言都落在队列上，
 * 用阻塞队列代替睡眠等待。
 */
class FeishuInboundServiceTest {

    private static final String OPEN_ID = "ou_test_user";
    private static final String CHAT_ID = "oc_test_chat";

    private FeishuInboundService service;
    private final LinkedBlockingQueue<FeishuInboundMessage> received = new LinkedBlockingQueue<>();

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void p2pText_isQueued() throws Exception {
        service = service(props());

        service.onEvent(textEvent("evt-1", "p2p", "你好", false, "user"));

        FeishuInboundMessage message = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals("你好", message.text());
        assertEquals(OPEN_ID, message.replyId(), "单聊回 open_id");
        assertEquals("open_id", message.receiveIdType());
    }

    @Test
    void groupWithMention_isQueuedAndMentionStripped() throws Exception {
        service = service(props());

        service.onEvent(textEvent("evt-2", "group", "@_user_1 帮我总结一下", true, "user"));

        FeishuInboundMessage message = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals("帮我总结一下", message.text(), "@_user_N 占位符不能进模型");
        assertEquals(CHAT_ID, message.replyId(), "群聊回 chat_id");
        assertEquals("chat_id", message.receiveIdType());
    }

    /** 开了 group_msg 之后，群里不 @ 机器人的普通消息也要处理 */
    @Test
    void groupWithoutMention_isQueued() throws Exception {
        service = service(props());

        service.onEvent(textEvent("evt-3", "group", "普通群消息", false, "user"));

        FeishuInboundMessage message = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(message, "群内普通消息应进入处理队列");
        assertEquals("普通群消息", message.text());
        assertEquals(CHAT_ID, message.replyId());
    }

    /** 机器人自己的消息必须挡住：否则会形成自回环 */
    @Test
    void botSender_isIgnored() throws Exception {
        service = service(props());

        service.onEvent(textEvent("evt-9", "group", "我是机器人", false, "app"));

        assertNull(received.poll(300, TimeUnit.MILLISECONDS), "sender_type=app 不该进队列");
    }

    @Test
    void nonWhitelistedUser_isIgnored() throws Exception {
        FeishuProperties props = props();
        props.getWs().setAllowedUsers(List.of("ou_someone_else"));
        service = service(props);

        service.onEvent(textEvent("evt-4", "p2p", "你好", false, "user"));

        assertNull(received.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void emptyWhitelist_isPairingMode() throws Exception {
        FeishuProperties props = props();
        props.getWs().setAllowedUsers(List.of());
        service = service(props);

        service.onEvent(textEvent("evt-5", "p2p", "你好", false, "user"));

        assertNull(received.poll(300, TimeUnit.MILLISECONDS), "配对模式只打印 open_id，不处理");
    }

    @Test
    void duplicateEvent_isProcessedOnce() throws Exception {
        service = service(props());

        service.onEvent(textEvent("evt-6", "p2p", "你好", false, "user"));
        service.onEvent(textEvent("evt-6", "p2p", "你好", false, "user"));

        assertNotNull(received.poll(2, TimeUnit.SECONDS));
        assertNull(received.poll(300, TimeUnit.MILLISECONDS), "同一 event_id 只能进队列一次");
    }

    /** 纯图片消息：没有正文，用占位符过 @NotBlank，同时把 image_key 带上 */
    @Test
    void imageMessage_isQueuedWithAttachment() throws Exception {
        service = service(props());

        service.onEvent(rawEvent("evt-7", "p2p", "image", "{\"image_key\":\"img_key_1\"}", false, "user"));

        FeishuInboundMessage message = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals("[图片]", message.text());
        assertEquals(1, message.attachments().size());
        assertEquals("img_key_1", message.attachments().get(0).fileKey());
    }

    /** 富文本：标题 + 段落拼成正文，图片元素转成附件 */
    @Test
    void postMessage_isFlattenedToTextAndImage() throws Exception {
        service = service(props());
        String content = """
                {"title":"周报","content":[
                  [{"tag":"text","text":"本周完成："},{"tag":"text","text":"渠道扩展"}],
                  [{"tag":"img","image_key":"img_key_9"}]
                ]}""";

        service.onEvent(rawEvent("evt-10", "p2p", "post", content, false, "user"));

        FeishuInboundMessage message = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(message);
        assertTrue(message.text().contains("周报"), "标题应保留");
        assertTrue(message.text().contains("渠道扩展"), "段落正文应保留");
        assertTrue(message.text().contains("[图片]"), "图片位置应留占位");
        assertEquals(List.of("img_key_9"),
                message.attachments().stream().map(FeishuAttachment::fileKey).toList());
    }

    /** 只有 @、没有正文也没有图，仍不该调模型 */
    @Test
    void blankText_isIgnored() throws Exception {
        service = service(props());

        service.onEvent(textEvent("evt-8", "group", "@_user_1 ", true, "user"));

        assertNull(received.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void unsupportedType_isIgnored() throws Exception {
        service = service(props());

        service.onEvent(rawEvent("evt-11", "p2p", "file", "{\"file_key\":\"f1\"}", false, "user"));

        assertNull(received.poll(300, TimeUnit.MILLISECONDS), "文件消息暂不支持");
    }

    private FeishuInboundService service(FeishuProperties props) {
        return new FeishuInboundService(props, new FeishuEventDedup(), received::add);
    }

    private static FeishuProperties props() {
        FeishuProperties props = new FeishuProperties();
        props.getWs().setEnabled(true);
        props.getWs().setAllowedUsers(List.of(OPEN_ID));
        props.getWs().setQueueCapacity(8);
        return props;
    }

    private static P2MessageReceiveV1 textEvent(String eventId, String chatType, String text,
                                                boolean mention, String senderType) {
        return rawEvent(eventId, chatType, "text", "{\"text\":\"" + text + "\"}", mention, senderType);
    }

    private static P2MessageReceiveV1 rawEvent(String eventId, String chatType, String messageType,
                                               String content, boolean mention, String senderType) {
        EventMessage.Builder message = EventMessage.newBuilder()
                .messageId("om_" + eventId)
                .chatId(CHAT_ID)
                .chatType(chatType)
                .messageType(messageType)
                .content(content);
        if (mention) {
            message.mentions(new MentionEvent[]{new MentionEvent()});
        }
        EventSender sender = EventSender.newBuilder()
                .senderId(UserId.newBuilder().openId(OPEN_ID).build())
                .senderType(senderType)
                .build();
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setMessage(message.build());
        data.setSender(sender);
        Header header = new Header();
        header.setEventId(eventId);
        header.setEventType("im.message.receive_v1");
        P2MessageReceiveV1 event = new P2MessageReceiveV1();
        event.setHeader(header);
        event.setEvent(data);
        return event;
    }
}
