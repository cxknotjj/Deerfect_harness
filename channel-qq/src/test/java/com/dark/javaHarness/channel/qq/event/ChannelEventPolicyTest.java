package com.dark.javaHarness.channel.qq.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dark.javaHarness.channel.qq.client.NapCatProperties;
import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 程序层事件准入策略单测：默认行为等价旧逻辑；显式规则可产出候选/忽略。
 */
class ChannelEventPolicyTest {

    private ChannelEventPolicy policy(NapCatProperties props) {
        return new ChannelEventPolicy(props, new EventTextParser(props, new SentMessageTracker()));
    }

    private static NapCatProperties baseProps() {
        NapCatProperties props = new NapCatProperties();
        props.setSelfId("1");
        props.getGroupTrigger().setMode("at");
        props.getGroupTrigger().setPrefix("/ai");
        return props;
    }

    private static OneBotEvent groupMsg(long groupId, long userId, String raw) {
        return new OneBotEvent(1L, 1L, "message", "group", userId, groupId, 1L, raw, null, null);
    }

    private static OneBotEvent privateMsg(long userId, String raw) {
        return new OneBotEvent(1L, 1L, "message", "private", userId, null, 1L, raw, null, null);
    }

    @Test
    void privateWhitelist_blocksStrangerAndAllowsListedUser() {
        NapCatProperties props = baseProps();
        props.setPrivateAllowUsers("999");
        ChannelEventPolicy policy = policy(props);

        ChannelEventPolicy.Evaluation stranger = policy.evaluate(privateMsg(111L, "你好"));
        ChannelEventPolicy.Evaluation listed = policy.evaluate(privateMsg(999L, "你好"));

        assertEquals(ChannelEventPolicy.Decision.IGNORE, stranger.decision());
        assertEquals(ChannelEventPolicy.Decision.RESPOND, listed.decision());
    }

    @Test
    void groupAtMode_requiresAtOrReply() {
        ChannelEventPolicy policy = policy(baseProps());

        assertEquals(ChannelEventPolicy.Decision.RESPOND,
                policy.evaluate(groupMsg(123L, 678L, "[CQ:at,qq=1] 问题")).decision());
        assertEquals(ChannelEventPolicy.Decision.IGNORE,
                policy.evaluate(groupMsg(123L, 678L, "普通闲聊")).decision());
    }

    @Test
    void explicitCandidateRule_allowsNonAtMessageAsCandidate() {
        NapCatProperties props = baseProps();
        NapCatProperties.EventPolicy.Rule rule = new NapCatProperties.EventPolicy.Rule();
        rule.setSource("group");
        rule.setGroups("123");
        rule.setUsers("678");
        rule.setTrigger("any");
        rule.setDecision("candidate");
        props.getEventPolicy().setRules(List.of(rule));

        ChannelEventPolicy.Evaluation eval = policy(props).evaluate(groupMsg(123L, 678L, "这个接口报错了"));

        assertEquals(ChannelEventPolicy.Decision.CANDIDATE, eval.decision());
        assertEquals("这个接口报错了", eval.text());
    }

    @Test
    void explicitIgnoreRule_winsByFirstMatch() {
        NapCatProperties props = baseProps();
        NapCatProperties.EventPolicy.Rule ignore = new NapCatProperties.EventPolicy.Rule();
        ignore.setSource("group");
        ignore.setGroups("123");
        ignore.setDecision("ignore");
        NapCatProperties.EventPolicy.Rule respond = new NapCatProperties.EventPolicy.Rule();
        respond.setSource("any");
        respond.setDecision("respond");
        props.getEventPolicy().setRules(List.of(ignore, respond));

        assertEquals(ChannelEventPolicy.Decision.IGNORE,
                policy(props).evaluate(groupMsg(123L, 678L, "hi")).decision());
    }
}
