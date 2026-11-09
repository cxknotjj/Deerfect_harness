package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 入站第一跳单测：单聊/群聊接受、@ 剥离、白名单、去重、非文本忽略。
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

        service.onEvent(event("evt-1", "p2p", "你好", false));

        FeishuInboundMessage message = received.poll(2, TimeUnit.SECONDS);
        assertEquals("你好", message.text());
        assertEquals(OPEN_ID, message.replyId(), "单聊回 open_id");
        assertEquals("open_id", message.receiveIdType());
    }

    @Test
    void groupWithMention_isQueuedAndMentionStripped() throws Exception {
        service = service(props());

        service.onEvent(event("evt-2", "group", "@_user_1 帮我总结一下", true));

        FeishuInboundMessage message = received.poll(2, TimeUnit.SECONDS);
        assertEquals("帮我总结一下", message.text(), "@_user_N 占位符不能进模型");
        assertEquals(CHAT_ID, message.replyId(), "群聊回 chat_id");
        assertEquals("chat_id", message.receiveIdType());
    }

    @Test
    void groupWithoutMention_isIgnored() throws Exception {
        service = service(props());

        service.onEvent(event("evt-3", "group", "普通群消息", false));

        assertNull(received.poll(300, TimeUnit.MILLISECONDS), "没人 @ 机器人的群消息不该处理");
        assertEquals(0, service.backlog());
    }

    @Test
    void nonWhitelistedUser_isIgnored() throws Exception {
        FeishuProperties props = props();
        props.getWs().setAllowedUsers(List.of("ou_someone_else"));
        service = service(props);

        service.onEvent(event("evt-4", "p2p", "你好", false));

        assertNull(received.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void emptyWhitelist_isPairingMode() throws Exception {
        FeishuProperties props = props();
        props.getWs().setAllowedUsers(List.of());
        service = service(props);

        service.onEvent(event("evt-5", "p2p", "你好", false));

        assertNull(received.poll(300, TimeUnit.MILLISECONDS), "配对模式只打印 open_id，不处理");
    }

    @Test
    void duplicateEvent_isProcessedOnce() throws Exception {
        service = service(props());

        service.onEvent(event("evt-6", "p2p", "你好", false));
        service.onEvent(event("evt-6", "p2p", "你好", false));

        assertTrue(received.poll(2, TimeUnit.SECONDS) != null);
        assertNull(received.poll(300, TimeUnit.MILLISECONDS), "同一 event_id 只能进队列一次");
    }

    @Test
    void nonTextMessage_isIgnored() throws Exception {
        service = service(props());

        service.onEvent(event("evt-7", "p2p", null, false));

        assertNull(received.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void blankText_isIgnored() throws Exception {
        service = service(props());

        service.onEvent(event("evt-8", "group", "@_user_1 ", true));

        assertNull(received.poll(300, TimeUnit.MILLISECONDS), "只有 @、没有正文，不该调模型");
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

    private static P2MessageReceiveV1 event(String eventId, String chatType, String text, boolean mention) {
        EventMessage.Builder message = EventMessage.newBuilder()
                .messageId("om_" + eventId)
                .chatId(CHAT_ID)
                .chatType(chatType)
                .messageType(text == null ? "image" : "text")
                .content(text == null ? "{\"image_key\":\"img\"}" : "{\"text\":\"" + text + "\"}");
        if (mention) {
            message.mentions(new MentionEvent[]{new MentionEvent()});
        }
        EventSender sender = EventSender.newBuilder()
                .senderId(UserId.newBuilder().openId(OPEN_ID).build())
                .senderType("user")
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
