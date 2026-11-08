package com.dark.javaHarness.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.view.SessionMessagesView;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * SessionService 端口契约（高扇入基座）：用内存替身固化多轮会话读写语义，
 * 不依赖 MySQL / Spring 上下文。
 *
 * <p>替身按既有实现（server SessionServiceImpl）的可观察语义实现：建档返回字符串主键、
 * 批量写回保留逐条时间戳、历史视图还原 user/assistant 角色、切换 Agent 做存在性校验、
 * 会话分页按 ID 倒序、删除幂等。
 */
class SessionServiceContractTest {

    private InMemorySessionService service;

    @BeforeEach
    void setUp() {
        service = new InMemorySessionService();
    }

    @Test
    void createSession_returnsIdAndStoresSession() {
        String id = service.createSession("anonymous", "第一句提问");

        assertNotNull(id);
        SessionEntity session = service.getSession(id);
        assertNotNull(session);
        assertEquals("anonymous", session.getCreator());
        assertEquals("第一句提问", session.getSessionName());
        assertTrue(service.listMessages(id).isEmpty(), "新会话不应有历史消息");
    }

    @Test
    void saveContextThenLoadContext_keepsRoleOrderAndText() {
        String id = service.createSession("anonymous", "第一句");
        UserMessage user = new UserMessage("你好");
        AssistantMessage assistant = new AssistantMessage("你好，我是助手");

        service.saveContext(id, user, 1000L);
        service.saveContext(id, assistant, 2000L);

        List<Message> context = service.loadContext(id);
        assertEquals(2, context.size());
        assertEquals("你好", context.get(0).getText());
        assertEquals("你好，我是助手", context.get(1).getText());

        List<SessionMessagesView.Item> view = service.listMessages(id);
        assertEquals(List.of(
                new SessionMessagesView.Item("user", "你好", 1000L),
                new SessionMessagesView.Item("assistant", "你好，我是助手", 2000L)), view);
    }

    @Test
    void saveContextWithoutTs_usesCurrentTime() {
        String id = service.createSession("anonymous", "第一句");
        service.saveContext(id, new UserMessage("hi"));

        SessionMessagesView.Item item = service.listMessages(id).get(0);
        assertNotNull(item.ts(), "缺省时间戳应按写入时刻补齐");
    }

    @Test
    void saveContextBatch_preservesPerEntryTimestamps() {
        String id = service.createSession("anonymous", "第一句");
        service.saveContextBatch(id, List.of(
                new SessionMessageEntry(new UserMessage("问题"), 10L),
                new SessionMessageEntry(new AssistantMessage("回答"), 20L)));

        List<SessionMessagesView.Item> view = service.listMessages(id);
        assertEquals(List.of(
                new SessionMessagesView.Item("user", "问题", 10L),
                new SessionMessagesView.Item("assistant", "回答", 20L)), view);
    }

    @Test
    void touchSession_updatesLastQuestion() {
        String id = service.createSession("anonymous", "第一句");

        service.touchSession(id, "最新提问");

        assertEquals("最新提问", service.getSession(id).getLastQuestion());
    }

    @Test
    void switchAgent_validatesSessionAndAgent() {
        String id = service.createSession("anonymous", "第一句");

        assertThrows(IllegalArgumentException.class, () -> service.switchAgent("oops", 1L));
        assertThrows(IllegalArgumentException.class, () -> service.switchAgent("999", 1L));
        assertThrows(IllegalArgumentException.class, () -> service.switchAgent(id, 99L));

        service.switchAgent(id, 2L);
        assertEquals(2, service.getSession(id).getAgentId().intValue());
        // 幂等：重复绑定同值不报错
        service.switchAgent(id, 2L);
        assertEquals(2, service.getSession(id).getAgentId().intValue());
    }

    @Test
    void page_returnsNewestFirstAndRespectsSize() {
        String first = service.createSession("anonymous", "a");
        String second = service.createSession("anonymous", "b");
        String third = service.createSession("anonymous", "c");

        Page<SessionEntity> page = service.page(1, 2);

        assertEquals(3, page.getTotal(), "总数应覆盖全部会话");
        assertEquals(List.of(third, second), page.getRecords().stream()
                .map(s -> String.valueOf(s.getSessionId()))
                .toList(), "按会话ID降序（最新在前）");

        Page<SessionEntity> next = service.page(2, 2);
        assertEquals(List.of(first), next.getRecords().stream()
                .map(s -> String.valueOf(s.getSessionId()))
                .toList());
    }

    @Test
    void deleteSession_isIdempotentAndRemovesContext() {
        String id = service.createSession("anonymous", "第一句");
        service.saveContext(id, new UserMessage("hi"));

        service.deleteSession(id);
        assertNull(service.getSession(id));
        assertTrue(service.listMessages(id).isEmpty());
        service.deleteSession(id); // 已删除再删不报错
        service.deleteSession("missing");
    }

    @Test
    void summaryPort_roundTripsDroppedMessages() {
        String id = service.createSession("anonymous", "第一句");
        service.requestSummary(id, List.of(new UserMessage("旧1"), new AssistantMessage("旧2")));
        assertEquals("dropped:2", service.summaryOf(id));
        assertNull(service.summaryOf("missing"), "无摘要返回 null");
    }

    @Test
    void chatMemoryAdapter_delegatesToContextOperations() {
        String id = service.createSession("anonymous", "第一句");

        service.add(id, List.of(new UserMessage("hi")));
        assertEquals("hi", service.get(id).get(0).getText());

        service.clear(id);
        assertTrue(service.get(id).isEmpty());
    }

    /** 内存替身：仅实现契约测试需要的可观察语义，不模拟持久化 */
    private static final class InMemorySessionService implements SessionService {

        private final Map<String, SessionEntity> sessions = new LinkedHashMap<>();
        private final Map<String, List<Stored>> contexts = new LinkedHashMap<>();
        private final Map<String, String> summaries = new HashMap<>();
        private final Set<Long> knownAgents = Set.of(1L, 2L);
        private long sequence;

        private record Stored(Message message, Long ts) {
        }

        @Override
        public String createSession(String creator, String firstQuestion) {
            String id = String.valueOf(++sequence);
            SessionEntity session = new SessionEntity();
            session.setSessionId(Long.valueOf(id));
            session.setSessionName(firstQuestion);
            session.setCreator(creator);
            session.setLastQuestion(firstQuestion);
            session.setLastActiveAt(LocalDateTime.now());
            session.setIsDelete(0);
            sessions.put(id, session);
            return id;
        }

        @Override
        public List<Message> loadContext(String sessionId) {
            return contexts.getOrDefault(sessionId, List.of()).stream()
                    .map(Stored::message)
                    .toList();
        }

        @Override
        public List<SessionMessagesView.Item> listMessages(String sessionId) {
            return contexts.getOrDefault(sessionId, List.of()).stream()
                    .map(s -> new SessionMessagesView.Item(
                            roleOf(s.message()), s.message().getText(), s.ts()))
                    .toList();
        }

        /** 契约只要求「与 loadContext 同源同序、携带稳定 id」；内存替身按序号生成 id */
        @Override
        public List<SessionSnapshotItem> snapshotItems(String sessionId) {
            List<Stored> stored = contexts.getOrDefault(sessionId, List.of());
            List<SessionSnapshotItem> items = new ArrayList<>();
            for (int i = 0; i < stored.size(); i++) {
                Stored s = stored.get(i);
                items.add(new SessionSnapshotItem("m" + i, roleOf(s.message()), s.message().getText(), s.ts()));
            }
            return items;
        }

        @Override
        public void saveContext(String sessionId, Message message) {
            saveContext(sessionId, message, System.currentTimeMillis());
        }

        @Override
        public void saveContext(String sessionId, Message message, Long ts) {
            contexts.computeIfAbsent(sessionId, k -> new ArrayList<>())
                    .add(new Stored(message, ts == null ? System.currentTimeMillis() : ts));
        }

        @Override
        public void saveContextBatch(String sessionId, List<SessionMessageEntry> entries) {
            for (SessionMessageEntry entry : entries) {
                saveContext(sessionId, entry.message(), entry.ts());
            }
        }

        @Override
        public void touchSession(String sessionId, String lastQuestion) {
            SessionEntity session = sessions.get(sessionId);
            if (session != null) {
                session.setLastQuestion(lastQuestion);
            }
        }

        @Override
        public SessionEntity getSession(String sessionId) {
            return sessions.get(sessionId);
        }

        @Override
        public void switchAgent(String sessionId, Long agentId) {
            Long sid = parseSessionId(sessionId);
            if (sid == null) {
                throw new IllegalArgumentException("非法 sessionId: " + sessionId);
            }
            if (agentId == null) {
                throw new IllegalArgumentException("agentId 不能为空");
            }
            SessionEntity session = sessions.get(sessionId);
            if (session == null) {
                throw new IllegalArgumentException("会话不存在: " + sessionId);
            }
            if (!knownAgents.contains(agentId)) {
                throw new IllegalArgumentException("agent 不存在: " + agentId);
            }
            if (session.getAgentId() != null && session.getAgentId().longValue() == agentId) {
                return; // 幂等：已绑定同值跳过
            }
            session.setAgentId(agentId.intValue());
        }

        @Override
        public Page<SessionEntity> page(long current, long size) {
            List<SessionEntity> sorted = sessions.values().stream()
                    .sorted(Comparator.comparing(SessionEntity::getSessionId).reversed())
                    .toList();
            long from = Math.min((current - 1) * size, sorted.size());
            long to = Math.min(from + size, sorted.size());
            Page<SessionEntity> page = new Page<>(current, size);
            page.setRecords(sorted.subList((int) from, (int) to));
            page.setTotal(sorted.size());
            return page;
        }

        @Override
        public void deleteSession(String sessionId) {
            sessions.remove(sessionId);
            contexts.remove(sessionId);
        }

        @Override
        public String summaryOf(String sessionId) {
            return summaries.get(sessionId);
        }

        @Override
        public void requestSummary(String sessionId, List<Message> droppedMessages) {
            // 契约替身：把被裁消息条数记成摘要，供 summaryOf 读取
            summaries.put(sessionId, "dropped:" + droppedMessages.size());
        }

        @Override
        public void deleteBySessionId(String sessionId) {
            // 契约替身：摘要按会话唯一，删除会话时一并清掉
            summaries.remove(sessionId);
        }

        @Override
        public List<Message> get(String conversationId) {
            return loadContext(conversationId);
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            for (Message message : messages) {
                saveContext(conversationId, message);
            }
        }

        @Override
        public void clear(String conversationId) {
            contexts.remove(conversationId);
        }

        private static String roleOf(Message message) {
            return message instanceof AssistantMessage ? "assistant" : "user";
        }

        private static Long parseSessionId(String sessionId) {
            try {
                return Long.valueOf(sessionId);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
