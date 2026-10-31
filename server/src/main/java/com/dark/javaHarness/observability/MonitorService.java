package com.dark.javaHarness.observability;

import com.dark.javaHarness.domain.dto.MonitorSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 运行监控查询：只读取，不写库。
 *
 * <p>渠道口径 = session.creator 冒号前的段（qq:&lt;uid&gt; → qq、cli → cli、anonymous/web → web），
 * 没有会话的调用归入 unknown。llm_call_log / tool_call_log 的 session_id 是字符串、session 表是
 * 数字，统一 CAST 关联；采用 LEFT JOIN 以便统计尚未绑定会话的调用。P95 取时间窗内最近 2000 条
 * 样本的最近秩，避免全表排序；小时桶按落库时刻聚合。
 */
@Service
public class MonitorService {

    private static final int MAX_WINDOW_HOURS = 168;
    private static final int MAX_ROWS = 200;
    private static final int P95_SAMPLE_CAP = 2000;

    /** 渠道表达式：未知为空、anonymous/web 归并为 web，其余取 creator 冒号前的段。 */
    private static final String CHANNEL_EXPR =
            "CASE WHEN s.creator IS NULL OR s.creator = '' THEN 'unknown'"
                    + " WHEN SUBSTRING_INDEX(s.creator, ':', 1) IN ('anonymous', 'web') THEN 'web'"
                    + " ELSE SUBSTRING_INDEX(s.creator, ':', 1) END";
    private static final String LLM_JOIN =
            " LEFT JOIN session s ON s.session_id = CAST(l.session_id AS UNSIGNED)";
    private static final String TOOL_JOIN =
            " LEFT JOIN session s ON s.session_id = CAST(t.session_id AS UNSIGNED)";

    /** 主库 JdbcTemplate：按类型注入会命中知识库的 vectorJdbcTemplate，故显式从 @Primary DataSource 构建。 */
    private final JdbcTemplate jdbc;
    private final RuntimeMetricsService runtimeMetrics;

    public MonitorService(DataSource dataSource, RuntimeMetricsService runtimeMetrics) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.runtimeMetrics = runtimeMetrics;
    }

    /** 查询指定时间窗（小时）、可选 agent 与渠道的运行监控快照。 */
    public MonitorSnapshot get(int hours, String agent, String channel, int limit) {
        int windowHours = Math.max(1, Math.min(MAX_WINDOW_HOURS, hours));
        int rowLimit = Math.max(1, Math.min(MAX_ROWS, limit));
        String agentName = agent == null || agent.isBlank() ? null : agent.trim();
        String channelName = channel == null || channel.isBlank() ? null : channel.trim();
        Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minusHours(windowHours));

        List<Object> llmArgs = new ArrayList<>();
        llmArgs.add(cutoff);
        List<Object> toolArgs = new ArrayList<>();
        toolArgs.add(cutoff);
        String llmWhere = " WHERE l.created_at >= ?";
        String toolWhere = " WHERE t.created_at >= ?";
        if (agentName != null) {
            llmWhere += " AND l.agent_name = ?";
            toolWhere += " AND t.agent_name = ?";
            llmArgs.add(agentName);
            toolArgs.add(agentName);
        }
        if (channelName != null) {
            llmWhere += " AND " + CHANNEL_EXPR + " = ?";
            toolWhere += " AND " + CHANNEL_EXPR + " = ?";
            llmArgs.add(channelName);
            toolArgs.add(channelName);
        }

        long[] callTotals = jdbc.queryForObject(
                "SELECT COUNT(*) AS calls,"
                        + " COALESCE(SUM(CASE WHEN l.status <> 'OK' THEN 1 ELSE 0 END), 0) AS errors,"
                        + " COALESCE(SUM(l.prompt_tokens), 0) AS prompt_tokens,"
                        + " COALESCE(SUM(l.completion_tokens), 0) AS completion_tokens,"
                        + " COALESCE(SUM(l.total_tokens), 0) AS total_tokens"
                        + " FROM llm_call_log l" + LLM_JOIN + llmWhere,
                llmArgs.toArray(),
                (rs, rowNum) -> new long[] {
                        rs.getLong("calls"), rs.getLong("errors"),
                        rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"),
                        rs.getLong("total_tokens")});

        List<Long> ttfts = new ArrayList<>();
        List<Long> durations = new ArrayList<>();
        jdbc.query("SELECT l.first_token_ms, l.duration_ms FROM llm_call_log l" + LLM_JOIN + llmWhere
                        + " ORDER BY l.id DESC LIMIT " + P95_SAMPLE_CAP,
                llmArgs.toArray(),
                rs -> {
                    Long ttft = nullableLong(rs, "first_token_ms");
                    if (ttft != null && ttft >= 0) {
                        ttfts.add(ttft);
                    }
                    durations.add(rs.getLong("duration_ms"));
                });

        long[] toolTotals = jdbc.queryForObject(
                "SELECT COUNT(*) AS calls,"
                        + " COALESCE(SUM(CASE WHEN t.status <> 'OK' THEN 1 ELSE 0 END), 0) AS errors"
                        + " FROM tool_call_log t" + TOOL_JOIN + toolWhere,
                toolArgs.toArray(),
                (rs, rowNum) -> new long[] {rs.getLong("calls"), rs.getLong("errors")});

        List<MonitorSnapshot.Channel> channels = queryChannels(cutoff, agentName);
        List<MonitorSnapshot.Session> sessions = querySessions(cutoff, agentName, channelName);
        List<MonitorSnapshot.LlmCall> llmCalls = queryLlmCalls(llmWhere, appendLimit(llmArgs, rowLimit));
        List<MonitorSnapshot.ToolCall> toolCalls = queryToolCalls(toolWhere, appendLimit(toolArgs, rowLimit));
        List<MonitorSnapshot.Hourly> hourly = queryHourly(llmWhere, llmArgs.toArray());

        long calls = callTotals[0];
        long errors = callTotals[1];
        MonitorSnapshot.Summary summary = new MonitorSnapshot.Summary(
                calls, errors,
                calls == 0 ? 0 : (double) errors / calls,
                average(ttfts), p95(ttfts),
                average(durations), p95(durations),
                callTotals[2], callTotals[3], callTotals[4],
                toolTotals[0], toolTotals[1]);

        return new MonitorSnapshot(summary, channels, sessions, llmCalls, toolCalls, hourly,
                runtimeMetrics.snapshot());
    }

    /** 渠道分布：不应用渠道筛选（保证筛选项始终完整），但应用时间窗与 agent 筛选。 */
    private List<MonitorSnapshot.Channel> queryChannels(Timestamp cutoff, String agentName) {
        String llmWhere = " WHERE l.created_at >= ?";
        List<Object> args = new ArrayList<>();
        args.add(cutoff);
        if (agentName != null) {
            llmWhere += " AND l.agent_name = ?";
            args.add(agentName);
        }
        Map<String, long[]> byChannel = new LinkedHashMap<>();
        jdbc.query("SELECT " + CHANNEL_EXPR + " AS channel, COUNT(*) AS calls,"
                        + " COALESCE(SUM(CASE WHEN l.status <> 'OK' THEN 1 ELSE 0 END), 0) AS errors,"
                        + " COALESCE(SUM(l.total_tokens), 0) AS tokens"
                        + " FROM llm_call_log l" + LLM_JOIN + llmWhere
                        + " GROUP BY channel ORDER BY calls DESC",
                args.toArray(),
                rs -> {
                    long[] row = byChannel.computeIfAbsent(rs.getString("channel"), k -> new long[5]);
                    row[0] = rs.getLong("calls");
                    row[1] = rs.getLong("errors");
                    row[2] = rs.getLong("tokens");
                });

        String toolWhere = " WHERE t.created_at >= ?";
        List<Object> toolArgs = new ArrayList<>();
        toolArgs.add(cutoff);
        if (agentName != null) {
            toolWhere += " AND t.agent_name = ?";
            toolArgs.add(agentName);
        }
        jdbc.query("SELECT " + CHANNEL_EXPR + " AS channel, COUNT(*) AS calls,"
                        + " COALESCE(SUM(CASE WHEN t.status <> 'OK' THEN 1 ELSE 0 END), 0) AS errors"
                        + " FROM tool_call_log t" + TOOL_JOIN + toolWhere
                        + " GROUP BY channel ORDER BY calls DESC",
                toolArgs.toArray(),
                rs -> {
                    long[] row = byChannel.computeIfAbsent(rs.getString("channel"), k -> new long[5]);
                    row[3] = rs.getLong("calls");
                    row[4] = rs.getLong("errors");
                });

        List<MonitorSnapshot.Channel> result = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byChannel.entrySet()) {
            long[] row = entry.getValue();
            result.add(new MonitorSnapshot.Channel(
                    entry.getKey(), row[0], row[1], row[2], row[3], row[4]));
        }
        return result;
    }

    /** 时间窗内活跃会话（按最近活跃倒序，最多 50 条），聚合窗口内 LLM 调用与 token。 */
    private List<MonitorSnapshot.Session> querySessions(Timestamp cutoff, String agentName, String channelName) {
        List<Object> args = new ArrayList<>();
        args.add(cutoff);
        args.add(cutoff);
        String where = " WHERE s.is_delete = 0 AND s.last_active_at >= ?";
        if (channelName != null) {
            where += " AND " + CHANNEL_EXPR + " = ?";
            args.add(channelName);
        }
        if (agentName != null) {
            where += " AND a.agent_name = ?";
            args.add(agentName);
        }
        return jdbc.query(
                "SELECT CAST(s.session_id AS CHAR) AS session_id, " + CHANNEL_EXPR + " AS channel,"
                        + " s.creator, s.session_name, a.agent_name,"
                        + " DATE_FORMAT(s.last_active_at, '%Y-%m-%d %H:%i:%s') AS last_active_at,"
                        + " COALESCE(x.calls, 0) AS calls, COALESCE(x.tokens, 0) AS tokens"
                        + " FROM session s"
                        + " LEFT JOIN agent a ON a.agent_id = s.agent_id"
                        + " LEFT JOIN (SELECT l.session_id, COUNT(*) AS calls,"
                        + "   COALESCE(SUM(l.total_tokens), 0) AS tokens"
                        + "   FROM llm_call_log l WHERE l.created_at >= ? GROUP BY l.session_id) x"
                        + "   ON x.session_id = CAST(s.session_id AS CHAR)" + where
                        + " ORDER BY s.last_active_at DESC LIMIT 50",
                args.toArray(),
                (rs, rowNum) -> new MonitorSnapshot.Session(
                        rs.getLong("session_id"),
                        rs.getString("channel"),
                        rs.getString("creator"),
                        rs.getString("agent_name"),
                        rs.getString("session_name"),
                        rs.getString("last_active_at"),
                        rs.getLong("calls"),
                        rs.getLong("tokens")));
    }

    private List<MonitorSnapshot.LlmCall> queryLlmCalls(String where, Object[] args) {
        return jdbc.query(
                "SELECT l.id, l.session_id, " + CHANNEL_EXPR + " AS channel, l.agent_name, l.model,"
                        + " l.call_kind, l.status, l.first_token_ms, l.duration_ms, l.prompt_tokens,"
                        + " l.completion_tokens, l.total_tokens, l.trace_id,"
                        + " DATE_FORMAT(l.created_at, '%Y-%m-%d %H:%i:%s') AS created_at"
                        + " FROM llm_call_log l" + LLM_JOIN + where
                        + " ORDER BY l.id DESC LIMIT ?",
                args,
                (rs, rowNum) -> new MonitorSnapshot.LlmCall(
                        rs.getLong("id"),
                        rs.getString("session_id"),
                        rs.getString("channel"),
                        rs.getString("agent_name"),
                        rs.getString("model"),
                        rs.getString("call_kind"),
                        rs.getString("status"),
                        nullableLong(rs, "first_token_ms"),
                        rs.getLong("duration_ms"),
                        nullableInt(rs, "prompt_tokens"),
                        nullableInt(rs, "completion_tokens"),
                        nullableInt(rs, "total_tokens"),
                        rs.getString("trace_id"),
                        rs.getString("created_at")));
    }

    private List<MonitorSnapshot.ToolCall> queryToolCalls(String where, Object[] args) {
        return jdbc.query(
                "SELECT t.id, t.session_id, " + CHANNEL_EXPR + " AS channel, t.agent_name, t.tool_name,"
                        + " t.server_name, t.status, t.duration_ms, t.trace_id,"
                        + " DATE_FORMAT(t.created_at, '%Y-%m-%d %H:%i:%s') AS created_at"
                        + " FROM tool_call_log t" + TOOL_JOIN + where
                        + " ORDER BY t.id DESC LIMIT ?",
                args,
                (rs, rowNum) -> new MonitorSnapshot.ToolCall(
                        rs.getLong("id"),
                        rs.getString("session_id"),
                        rs.getString("channel"),
                        rs.getString("agent_name"),
                        rs.getString("tool_name"),
                        rs.getString("server_name"),
                        rs.getString("status"),
                        rs.getLong("duration_ms"),
                        rs.getString("trace_id"),
                        rs.getString("created_at")));
    }

    private List<MonitorSnapshot.Hourly> queryHourly(String where, Object[] args) {
        return jdbc.query(
                "SELECT DATE_FORMAT(l.created_at, '%m-%d %H:00') AS bucket,"
                        + " COUNT(*) AS calls, COALESCE(SUM(l.total_tokens), 0) AS tokens,"
                        + " COALESCE(ROUND(AVG(l.first_token_ms)), 0) AS avg_ttft"
                        + " FROM llm_call_log l" + LLM_JOIN + where
                        + " GROUP BY bucket ORDER BY MIN(l.created_at)",
                args,
                (rs, rowNum) -> new MonitorSnapshot.Hourly(
                        rs.getString("bucket"),
                        rs.getLong("calls"),
                        rs.getLong("tokens"),
                        rs.getLong("avg_ttft")));
    }

    private static Object[] appendLimit(List<Object> args, int limit) {
        List<Object> withLimit = new ArrayList<>(args);
        withLimit.add(limit);
        return withLimit.toArray();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private static long average(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    /** 最近秩 P95：样本升序后取第 ceil(n*0.95) 个，样本为空返回 0。 */
    private static long p95(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
