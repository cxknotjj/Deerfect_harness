package com.dark.javaHarness.contract.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.contract.model.SessionInfo;
import com.dark.javaHarness.contract.model.SessionMessageEntry;
import com.dark.javaHarness.contract.model.SessionSnapshotItem;
import com.dark.javaHarness.view.PageResult;
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
 *
 * <p>替身内部用自己的可变行对象（{@code Row}），对外只吐 {@link SessionInfo}——
 * 与实现侧「实体留 server、契约只暴露视图」保持一致。
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
        SessionInfo session = service.getSession(id);
        assertNotNull(session);
        assertEquals("anonymous", session.creator());
        assertEquals("第一句提问", session.name());
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

        assertEquals("最新提问", service.getSession(id).lastQuestion());
    }

    @Test
    void switchAgent_validatesSessionAndAgent() {
        String id = service.createSession("anonymous", "第一句");

        assertThrows(IllegalArgumentException.class, () -> service.switchAgent("oops", 1L));
        assertThrows(IllegalArgumentException.class, () -> service.switchAgent("999", 1L));
        assertThrows(IllegalArgumentException.class, () -> service.switchAgent(id, 99L));

        service.switchAgent(id, 2L);
        assertEquals(2, service.getSession(id).agentId().intValue());
        // 幂等：重复绑定同值不报错
        service.switchAgent(id, 2L);
        assertEquals(2, service.getSession(id).agentId().intValue());
    }

    @Test
    void page_returnsNewestFirstAndRespectsSize() {
        String first = service.createSession("anonymous", "a");
        String second = service.createSession("anonymous", "b");
        String third = service.createSession("anonymous", "c");

        PageResult<SessionInfo> page = service.page(1, 2);

        assertEquals(3, page.total(), "总数应覆盖全部会话");
        assertEquals(List.of(third, second), page.list().stream()
                .map(s -> String.valueOf(s.id()))
                .toList(), "按会话ID降序（最新在前）");

        PageResult<SessionInfo> next = service.page(2, 2);
        assertEquals(List.of(first), next.list().stream()
                .map(s -> String.valueOf(s.id()))
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

        /** 可变行：替代此前的 MyBatis 实体，契约测试不需要数据库注解 */
        private static final class Row {
            Long id;
            String name;
            String creator;
            String lastQuestion;
            Integer agentId;
            LocalDateTime lastActiveAt;
        }

        private final Map<String, Row> sessions = new LinkedHashMap<>();
        private final Map<String, List<Stored>> contexts = new LinkedHashMap<>();
        private final Map<String, String> summaries = new HashMap<>();
        private final Set<Long> knownAgents = Set.of(1L, 2L);
        private long sequence;

        private record Stored(Message message, Long ts) {
        }

        @Override
        public String createSession(String creator, String firstQuestion) {
            String id = String.valueOf(++sequence);
            Row row = new Row();
            row.id = Long.valueOf(id);
            row.name = firstQuestion;
            row.creator = creator;
            row.lastQuestion = firstQuestion;
            row.lastActiveAt = LocalDateTime.now();
            sessions.put(id, row);
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
            Row row = sessions.get(sessionId);
            if (row != null) {
                row.lastQuestion = lastQuestion;
            }
        }

        @Override
        public SessionInfo getSession(String sessionId) {
            Row row = sessions.get(sessionId);
            return row == null ? null : toInfo(row);
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
            Row row = sessions.get(sessionId);
            if (row == null) {
                throw new IllegalArgumentException("会话不存在: " + sessionId);
            }
            if (!knownAgents.contains(agentId)) {
                throw new IllegalArgumentException("agent 不存在: " + agentId);
            }
            if (row.agentId != null && row.agentId.longValue() == agentId) {
                return; // 幂等：已绑定同值跳过
            }
            row.agentId = agentId.intValue();
        }

        @Override
        public PageResult<SessionInfo> page(long current, long size) {
            List<Row> sorted = sessions.values().stream()
                    .sorted(Comparator.comparing((Row r) -> r.id).reversed())
                    .toList();
            long from = Math.min((current - 1) * size, sorted.size());
            long to = Math.min(from + size, sorted.size());
            return new PageResult<>(
                    sorted.subList((int) from, (int) to).stream()
                            .map(InMemorySessionService::toInfo).toList(),
                    sorted.size(),
                    current,
                    size);
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

        private static SessionInfo toInfo(Row row) {
            return new SessionInfo(row.id, row.name, row.creator, row.lastQuestion,
                    row.agentId, row.lastActiveAt);
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
