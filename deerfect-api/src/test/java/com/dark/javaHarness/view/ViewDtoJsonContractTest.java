package com.dark.javaHarness.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 对外 DTO 的 JSON 字段名契约（harness-api 是跨渠道共享的序列化口径）：
 * 字段名一旦变化，CLI / web / QQ 渠道与外部调用方都会静默读不到值。
 *
 * <p>本测试只固化「JSON 键名与嵌套结构」，不校验字段顺序与具体值。
 */
class ViewDtoJsonContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Set<String> keysOf(Object dto) throws Exception {
        Map<String, Object> json = MAPPER.readValue(MAPPER.writeValueAsString(dto),
                new TypeReference<Map<String, Object>>() {
                });
        return json.keySet();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> firstElementKeys(Object dto, String field) throws Exception {
        Map<String, Object> json = MAPPER.readValue(MAPPER.writeValueAsString(dto),
                new TypeReference<Map<String, Object>>() {
                });
        Map<String, Object> nested = (Map<String, Object>) ((List<Object>) json.get(field)).get(0);
        return nested.keySet();
    }

    @Test
    void chatRequest_jsonKeys() throws Exception {
        assertEquals(Set.of("message", "sessionId", "agentId", "routing", "channel", "attachments"),
                keysOf(new ChatRequest("hi", "s1", 2L, "graph", "cli")));
    }

    @Test
    void chatResponse_jsonKeys() throws Exception {
        ChatResponse response = ChatResponse.success("s1", true, "g1", "答复",
                List.of(new KnowledgeSource("a.md", "A", 0.9)), "general",
                Set.of("voice:abc"),
                new RouteInfo("agentic", 0.95, List.of(), List.of(), "rule", "RULE"));

        assertEquals(Set.of("sessionId", "newSession", "goalId", "status", "reply", "error",
                "sources", "agent", "flags", "route"), keysOf(response));
        assertEquals(Set.of("docName", "title", "score"), keysOf(response.sources().get(0)));
        assertEquals(Set.of("path", "confidence", "workstreams", "deliverables", "reason", "source"),
                keysOf(response.route()));
    }

    @Test
    void chatResponse_factoriesKeepStatusAndEmptyFlags() {
        ChatResponse success = ChatResponse.success("s1", false, "g1", "答复", null, "general",
                Set.of(), null);
        assertEquals("SUCCEEDED", success.status());
        assertNull(success.error());

        ChatResponse failure = ChatResponse.failure("s1", false, "g1", "boom", "general", null);
        assertEquals("FAILED", failure.status());
        assertNull(failure.reply());
        assertNull(failure.sources());
        assertTrue(failure.flags().isEmpty());
    }

    @Test
    void sseMeta_jsonKeys() throws Exception {
        assertEquals(Set.of("sessionId", "newSession", "goalId", "status", "error", "sources", "route"),
                keysOf(new SseMeta("s1", false, null, "SUCCEEDED", null, null, null)));
    }

    @Test
    void routeAndGoalViews_jsonKeys() throws Exception {
        assertEquals(Set.of("path", "confidence", "workstreams", "deliverables", "reason", "source"),
                keysOf(new RouteInfo("graph", 1.0, List.of("a"), List.of("b"), "override", "OVERRIDE")));
        assertEquals(Set.of("id", "objective", "status", "summary"),
                keysOf(new GoalView("g1", "目标", "RUNNING", null)));
        assertEquals(Set.of("goals"), keysOf(new GoalsView(List.of(new GoalView("g1", "目标", "PENDING", null)))));
        assertEquals(Set.of("goalId", "status"), keysOf(new GoalStatusView("g1", "FAILED")));
        assertEquals(Set.of("goalId", "status"), keysOf(new SubmitView("g1", "PENDING")));
    }

    @Test
    void agentViews_jsonKeys() throws Exception {
        assertEquals(Set.of("id", "name"), keysOf(new AgentItemView(1L, "general")));
        assertEquals(Set.of("agents"), keysOf(new AgentsView(List.of(new AgentItemView(1L, "general")))));
    }

    @Test
    void sessionViews_jsonKeys() throws Exception {
        SessionMessagesView messages = new SessionMessagesView("s1",
                List.of(new SessionMessagesView.Item("user", "hi", 100L)));
        assertEquals(Set.of("sessionId", "messages"), keysOf(messages));
        assertEquals(Set.of("role", "content", "ts"), firstElementKeys(messages, "messages"));

        SessionPageView page = new SessionPageView(1, 10, 25, 3,
                List.of(new SessionPageView.Item("s1", "会话", "anonymous", "hi", 100L)));
        assertEquals(Set.of("page", "size", "total", "pages", "sessions"), keysOf(page));
        assertEquals(Set.of("id", "name", "creator", "lastQuestion", "lastActiveAt"),
                firstElementKeys(page, "sessions"));

        assertEquals(Set.of("sessionId", "agentId", "agentName"),
                keysOf(new SessionAgentView("s1", 2L, "writer")));
        assertEquals(Set.of("sessionId", "sessionName"),
                keysOf(new SessionCreatedView("s1", "会话")));
    }

    @Test
    void pageResultAndKnowledgeSource_jsonKeys() throws Exception {
        assertEquals(Set.of("list", "total", "page", "size"),
                keysOf(new PageResult<>(List.of("a"), 1, 1, 10)));
        assertEquals(Set.of("docName", "title", "score"),
                keysOf(new KnowledgeSource("a.md", "A", 0.5)));
    }

    @Test
    void errorResponse_jsonKeys() throws Exception {
        assertEquals(Set.of("code", "message"), keysOf(ErrorResponse.of(400, "bad request")));
        assertEquals(400, ErrorResponse.of(400, "bad request").code());
    }
}
