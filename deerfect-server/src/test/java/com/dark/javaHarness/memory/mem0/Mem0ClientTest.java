package com.dark.javaHarness.memory.mem0;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dark.javaHarness.memory.plugin.MemoryHit;
import com.dark.javaHarness.memory.plugin.MemoryQuery;
import com.dark.javaHarness.memory.plugin.MemoryScope;
import com.dark.javaHarness.memory.plugin.MemoryTurn;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * mem0 OSS REST 契约：路径、鉴权头、请求体、响应解析与失败降级。
 * spec:001
 */
class Mem0ClientTest {

    private static final String BASE = "http://mem0.test";

    private MockRestServiceServer server;
    private SimpleMeterRegistry meters;
    private Mem0Client client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE)
                .defaultHeader("X-API-Key", "m0sk_test");
        server = MockRestServiceServer.bindTo(builder).build();
        meters = new SimpleMeterRegistry();
        client = new Mem0Client(builder.build(), meters);
    }

    @Test
    void searchParsesResults() {
        server.expect(requestTo(BASE + "/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "m0sk_test"))
                .andExpect(content().json("""
                        {"query":"我叫什么","user_id":"cli","agent_id":"general","limit":5}
                        """))
                .andRespond(withSuccess("""
                        {"results":[{"id":"m1","memory":"用户叫小林","score":0.82}]}
                        """, MediaType.APPLICATION_JSON));

        List<MemoryHit> hits = client.search(query());

        assertEquals(1, hits.size());
        assertEquals("m1", hits.get(0).id());
        assertEquals("用户叫小林", hits.get(0).text());
        assertEquals(0.82, hits.get(0).score());
        assertEquals(1.0, meters.counter("harness.memory.plugin.calls",
                "plugin", "mem0", "op", "search", "result", "ok").count());
        server.verify();
    }

    @Test
    void searchReturnsEmptyOnServerError() {
        server.expect(requestTo(BASE + "/search"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertTrue(client.search(query()).isEmpty());
        server.verify();
    }

    @Test
    void searchReturnsEmptyOnInvalidBody() {
        server.expect(requestTo(BASE + "/search"))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertTrue(client.search(query()).isEmpty());
        server.verify();
    }

    @Test
    void addPostsTurnMessagesAndMetadata() {
        server.expect(requestTo(BASE + "/memories"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "m0sk_test"))
                .andExpect(content().json("""
                        {
                          "messages":[
                            {"role":"user","content":"我叫小林"},
                            {"role":"assistant","content":"记住了"}
                          ],
                          "user_id":"cli",
                          "agent_id":"general",
                          "run_id":"s1",
                          "metadata":{"app":"java-harness","turn_id":"t1","channel":"cli"}
                        }
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.add(turn());

        server.verify();
    }

    @Test
    void addSwallowsServerError() {
        server.expect(requestTo(BASE + "/memories"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        client.add(turn());

        server.verify();
    }

    private static MemoryQuery query() {
        return new MemoryQuery("我叫什么", new MemoryScope("cli", "general", "s1"), 5);
    }

    private static MemoryTurn turn() {
        return new MemoryTurn(new MemoryScope("cli", "general", "s1"),
                "我叫小林", "记住了", "t1", "cli", System.currentTimeMillis());
    }
}
