package com.dark.javaHarness.memory.mem0;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import com.dark.javaHarness.memory.plugin.MemoryHit;
import com.dark.javaHarness.memory.plugin.MemoryQuery;
import com.dark.javaHarness.memory.plugin.MemoryTurn;

/**
 * mem0 OSS REST 客户端：只做 add / search，两条路径都是 best-effort。
 *
 * <p>外部系统属于系统边界：连接失败、超时、4xx/5xx、响应体非法都在本类捕获，
 * 记录 WARN 与指标后返回空结果/放弃写入，不向对话主流程抛异常。
 * 日志不输出记忆原文与 API Key。客户端实例由 {@link Mem0Config} 在插件启用时装配。
 */
public class Mem0Client {

    private static final Logger log = LoggerFactory.getLogger(Mem0Client.class);

    private static final String PLUGIN = "mem0";

    private final RestClient restClient;
    private final MeterRegistry meters;

    public Mem0Client(RestClient restClient, MeterRegistry meters) {
        this.restClient = restClient;
        this.meters = meters;
    }

    /** 检索相关记忆；失败返回空列表 */
    public List<MemoryHit> search(MemoryQuery query) {
        long start = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query.query());
            body.put("user_id", query.scope().userId());
            body.put("agent_id", query.scope().agentName());
            body.put("limit", query.topK());
            @SuppressWarnings("unchecked")
            Map<String, Object> root = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            List<MemoryHit> hits = parseHits(root);
            record("search", hits.isEmpty() ? "empty" : "ok", start);
            log.info("[memory] plugin={} op=search result={} hits={} costMs={}",
                    PLUGIN, hits.isEmpty() ? "empty" : "ok", hits.size(), costMs(start));
            return hits;
        } catch (Exception e) {
            record("search", "error", start);
            log.warn("[memory] plugin={} op=search result=error errorClass={} costMs={}",
                    PLUGIN, e.getClass().getSimpleName(), costMs(start));
            return List.of();
        }
    }

    /** 写入一轮对话；失败只记录 */
    public void add(MemoryTurn turn) {
        long start = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("messages", List.of(
                    Map.of("role", "user", "content", turn.userMessage()),
                    Map.of("role", "assistant", "content", turn.assistantReply())));
            body.put("user_id", turn.scope().userId());
            body.put("agent_id", turn.scope().agentName());
            if (turn.scope().sessionId() != null && !turn.scope().sessionId().isBlank()) {
                body.put("run_id", turn.scope().sessionId());
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("app", "java-harness");
            metadata.put("turn_id", turn.turnId());
            if (turn.channel() != null && !turn.channel().isBlank()) {
                metadata.put("channel", turn.channel());
            }
            body.put("metadata", metadata);
            restClient.post()
                    .uri("/memories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            record("remember", "ok", start);
            log.info("[memory] plugin={} op=remember result=ok turnId={} costMs={}",
                    PLUGIN, turn.turnId(), costMs(start));
        } catch (Exception e) {
            record("remember", "error", start);
            log.warn("[memory] plugin={} op=remember result=error errorClass={} turnId={} costMs={}",
                    PLUGIN, e.getClass().getSimpleName(), turn.turnId(), costMs(start));
        }
    }

    private static List<MemoryHit> parseHits(Map<String, Object> root) {
        if (root == null || !(root.get("results") instanceof List<?> results)) {
            return List.of();
        }
        List<MemoryHit> hits = new ArrayList<>();
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Object memory = row.get("memory");
            if (memory == null || memory.toString().isBlank()) {
                continue;
            }
            Object id = row.get("id");
            Object rawScore = row.get("score");
            Double score = rawScore instanceof Number number ? number.doubleValue() : null;
            hits.add(new MemoryHit(id == null ? null : id.toString(), memory.toString(), score, Map.of()));
        }
        return List.copyOf(hits);
    }

    /** 指标：调用次数与耗时按 op/result 维度打标签（不携带记忆内容） */
    private void record(String op, String result, long startNanos) {
        meters.counter("harness.memory.plugin.calls", "plugin", PLUGIN, "op", op, "result", result)
                .increment();
        Timer.builder("harness.memory.plugin.latency")
                .tag("plugin", PLUGIN)
                .tag("op", op)
                .register(meters)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static long costMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
