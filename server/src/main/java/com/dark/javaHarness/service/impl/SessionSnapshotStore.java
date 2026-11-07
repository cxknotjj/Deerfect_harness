package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.domain.entity.SessionMessageArchiveEntity;
import com.dark.javaHarness.domain.entity.SessionMessageEntity;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.mapper.SessionMessageArchiveMapper;
import com.dark.javaHarness.mapper.SessionMessageMapper;
import com.dark.javaHarness.service.SessionMessageEntry;
import com.dark.javaHarness.service.SessionSnapshotItem;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 会话上下文快照存储：session_messages 单行 JSON 快照的读、追加写、裁剪与归档。
 *
 * <p>存储模型：每个会话仅一行，content 以 JSON 存该会话的完整上下文
 * （{@code [{"id":"m-...","role":"user","content":"...","ts":"..."}]}）。
 *
 * <p><b>为什么这些职责放在同一个类里</b>（自 {@link SessionServiceImpl} 抽出，2026-11-07）：
 * 读路径、裁剪规则（保留条数 / 字符上限 / 配对保护）、归档落库、序列化重试、写回行锁必须
 * 同步演进——裁剪丢了哪条，归档就要落哪条；改了配对保护，读出来的上下文才仍然合法。
 * 它们留在同一个类里是内聚，不是耦合。抽出去的只是「会话元数据」（建档 / 自动改名 /
 * 切 Agent / 分页 / 软删），那部分不关心快照怎么存。
 *
 * <p><b>事务边界在调用方</b>：{@link #appendBatch} 内部的 {@code FOR UPDATE} 行锁必须与
 * 读-改-写处于同一事务才有意义，所以本类不加 {@code @Transactional}，由
 * {@link SessionServiceImpl} 的写入口标注。
 */
@Component
public class SessionSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SessionSnapshotStore.class);

    /** 默认租户（未做多租户前统一使用） */
    private static final String DEFAULT_TENANT = "default";
    /** 快照行的角色标记：content 存的是全量上下文 JSON */
    private static final String ROLE_CONTEXT = "context";
    /** 快照正文总字符上限（MEDIUMTEXT 16MB，按最坏 UTF-8 4 字节/码点预留余量；0 不限制） */
    private static final int SNAPSHOT_MAX_CHARS = 4_000_000;

    private final SessionMapper sessionMapper;
    private final SessionMessageMapper messageMapper;
    /** 快照裁剪转归档（recall_history 数据源） */
    private final SessionMessageArchiveMapper archiveMapper;
    private final ObjectMapper objectMapper;
    /** 快照保留消息条数上限（超出丢弃最旧；0 = 不限制） */
    private final int snapshotMaxMessages;

    public SessionSnapshotStore(SessionMapper sessionMapper,
                                SessionMessageMapper messageMapper,
                                SessionMessageArchiveMapper archiveMapper,
                                ObjectMapper objectMapper,
                                @Value("${app.session.snapshot-max-messages:200}") int snapshotMaxMessages) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.archiveMapper = archiveMapper;
        this.objectMapper = objectMapper;
        this.snapshotMaxMessages = snapshotMaxMessages;
    }

    /** 读取快照 item 列表（无会话/无行/解析失败返回空列表） */
    public List<Map<String, String>> readItems(String sessionId) {
        return parseSnapshotItems(latestSnapshotRow(sessionId));
    }

    /** 读取带稳定 id 与 ts 的快照条目（增量消费方用它做「上次处理到哪一条」的游标） */
    public List<SessionSnapshotItem> items(String sessionId) {
        List<SessionSnapshotItem> items = new ArrayList<>();
        for (Map<String, String> item : readItems(sessionId)) {
            items.add(new SessionSnapshotItem(
                    item.get("id"), item.get("role"), item.get("content"), parseTs(item.get("ts"))));
        }
        return items;
    }

    /**
     * 批量追加消息到该会话唯一上下文行（不存在则新建）：一次行锁 / 一次快照读取 /
     * 一次序列化 / 一次 UPDATE。逐条写回会把整份上下文 JSON 读改写两遍，长会话下
     * 写放大与锁持有时间都翻倍（优化审查 2026-11-03）。
     *
     * <p>并发安全（优化审查 2026-09-25 中危项）：先以 FOR UPDATE 锁会话行作为该会话上下文
     * 写回的互斥点，读-改-写全程串行——并发写同一会话（流式收尾 + QQ 同步、连续两轮竞速）
     * 不再互相覆盖丢消息。存在性判定与互斥锁合并为同一次查询（2026-11-03）。
     *
     * @return 是否真正写入（会话不存在/已软删、序列化失败返回 false）
     */
    public boolean appendBatch(String sessionId, List<SessionMessageEntry> entries) {
        if (sessionId == null || sessionId.isBlank() || entries == null || entries.isEmpty()) {
            return false;
        }
        // 防复活守卫：会话已软删/不存在时跳过写回，避免已删会话在流收尾时重建孤儿快照
        Long sid = parseSessionId(sessionId);
        if (sid == null || lockSessionRow(sid) == null) {
            log.info("会话不存在或已删除，跳过上下文写回 sessionId={}", sessionId);
            return false;
        }
        // 读取已有上下文，按顺序追加本批消息
        SessionMessageEntity existing = latestSnapshotRow(sessionId);
        List<Map<String, String>> items = new ArrayList<>(parseSnapshotItems(existing));
        // 存量 item 无 id：在此轮写回时补齐，保证后续裁剪归档有稳定定位符
        items.forEach(SessionSnapshotStore::ensureMessageId);
        for (SessionMessageEntry entry : entries) {
            if (entry != null && entry.message() != null) {
                items.add(snapshotItem(entry.message(), entry.ts()));
            }
        }
        TrimResult trimmed = trimSnapshot(items);

        String json = serializeWithRetry(sessionId, items);
        if (json == null) {
            return false; // 两次序列化均失败：本轮上下文副本丢失（回复已送达），仅记错误日志
        }
        // 先归档再写快照：同事务内任一失败一起回滚，不产生「快照已丢、归档没落」的半写
        archiveTrimmed(sessionId, trimmed);
        if (existing != null) {
            UpdateWrapper<SessionMessageEntity> uw = new UpdateWrapper<>();
            uw.eq("id", existing.getId())
                    .set("content", json);
            messageMapper.update(null, uw);
        } else {
            SessionMessageEntity row = new SessionMessageEntity();
            row.setSessionId(sessionId);
            row.setTenantId(DEFAULT_TENANT);
            row.setRole(ROLE_CONTEXT);
            row.setContent(json);
            row.setTokenCount(0);
            row.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(row);
        }
        return true;
    }

    /** 删除该会话的全部快照行（会话删除 / 上下文清空）；返回删除条数 */
    public int deleteBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        QueryWrapper<SessionMessageEntity> qw = new QueryWrapper<>();
        qw.eq("session_id", sessionId);
        return messageMapper.delete(qw);
    }

    /** 查询该会话唯一的上下文行（保留取最新一条以兼容历史多行数据；无会话/无行返回 null） */
    private SessionMessageEntity latestSnapshotRow(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        QueryWrapper<SessionMessageEntity> qw = new QueryWrapper<>();
        qw.eq("session_id", sessionId)
                .orderByDesc("id")
                .last("LIMIT 1");
        return messageMapper.selectOne(qw);
    }

    /** 解析快照行 content 为 item 列表（空行/解析失败返回空列表，按空上下文处理） */
    private List<Map<String, String>> parseSnapshotItems(SessionMessageEntity row) {
        if (row == null || row.getContent() == null || row.getContent().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    row.getContent(), new TypeReference<List<Map<String, String>>>() {});
        } catch (JacksonException e) {
            log.warn("解析会话上下文快照失败 sessionId={}，按空上下文处理", row.getSessionId(), e);
            return List.of();
        }
    }

    /** 快照 item 的 ts（毫秒字符串）→ Long；缺失/非法返回 null（表驱动化之前的旧快照兼容） */
    private static Long parseTs(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单条消息 → 快照 item；ts 非空时以字符串形态入快照（保持 Map&lt;String,String&gt; 反序列化兼容） */
    private static Map<String, String> snapshotItem(Message message, Long ts) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("id", newMessageId());
        item.put("role", roleOf(message));
        item.put("content", message.getText());
        if (ts != null) {
            // 时间戳仅展示用，loadContext 忽略
            item.put("ts", String.valueOf(ts));
        }
        return item;
    }

    /** 会话行锁（FOR UPDATE）：同一会话的上下文写回以该锁串行化；返回行本身（null=不存在/已软删）；须在事务内调用 */
    private SessionEntity lockSessionRow(Long sid) {
        QueryWrapper<SessionEntity> lw = new QueryWrapper<>();
        lw.select("session_id").eq("session_id", sid).last("FOR UPDATE");
        return sessionMapper.selectOne(lw);
    }

    /**
     * 快照裁剪：超过保留条数时丢弃最旧的消息；总字符超上限时同样从最旧丢弃，
     * 单条仍超限则按码点截断正文。保证写入始终在列容量内（MEDIUMTEXT），
     * 避免长会话把上下文撑爆后整轮写回失败、静默丢历史。
     */
    private TrimResult trimSnapshot(List<Map<String, String>> items) {
        List<Map<String, String>> removed = new ArrayList<>();
        List<Map<String, String>> tails = new ArrayList<>();
        if (snapshotMaxMessages > 0 && items.size() > snapshotMaxMessages) {
            List<Map<String, String>> dropped = items.subList(0, items.size() - snapshotMaxMessages);
            removed.addAll(dropped);
            dropped.clear();
        }
        long chars = 0;
        for (Map<String, String> it : items) {
            String content = it.get("content");
            chars += content == null ? 0 : content.length();
        }
        while (items.size() > 1 && chars > SNAPSHOT_MAX_CHARS) {
            Map<String, String> dropped = items.remove(0);
            removed.add(dropped);
            String content = dropped.get("content");
            chars -= content == null ? 0 : content.length();
        }
        if (chars > SNAPSHOT_MAX_CHARS && !items.isEmpty()) {
            Map<String, String> only = items.get(0);
            String content = only.get("content");
            if (content != null) {
                // 按码点截断，避免从代理对中间切开产生非法字符；
                // 先比较码点数——offsetByCodePoints 的偏移不能超过实际码点数
                int codePoints = content.codePointCount(0, content.length());
                if (codePoints > SNAPSHOT_MAX_CHARS) {
                    int end = content.offsetByCodePoints(0, SNAPSHOT_MAX_CHARS);
                    // 截断尾部先归档，避免单条超限时原文后半段永久丢失
                    Map<String, String> tail = new LinkedHashMap<>();
                    tail.put("id", only.get("id"));
                    tail.put("role", only.get("role"));
                    tail.put("content", content.substring(end));
                    if (only.get("ts") != null) {
                        tail.put("ts", only.get("ts"));
                    }
                    tails.add(tail);
                    only.put("content", content.substring(0, end));
                }
            }
        }
        // 配对保护：上下文尽量不以 assistant 开头（严格交替校验的模型会 400，且保留半个问答对无意义）；
        // 保留至少一条，避免极端情况下把快照清空
        while (items.size() > 1 && "assistant".equals(items.get(0).get("role"))) {
            removed.add(items.remove(0));
        }
        return new TrimResult(removed, tails);
    }

    /** 快照裁剪结果：整条被移除的消息 + 单条超限截断的尾部（两者都先落归档） */
    private record TrimResult(List<Map<String, String>> removed, List<Map<String, String>> tails) {
    }

    /** 裁剪结果落归档（与快照写回同事务） */
    private void archiveTrimmed(String sessionId, TrimResult trimmed) {
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, String> item : trimmed.removed()) {
            insertArchive(sessionId, item, "full", now);
        }
        for (Map<String, String> item : trimmed.tails()) {
            insertArchive(sessionId, item, "tail", now);
        }
    }

    private void insertArchive(String sessionId, Map<String, String> item, String part, LocalDateTime now) {
        SessionMessageArchiveEntity row = new SessionMessageArchiveEntity();
        row.setSessionId(sessionId);
        row.setMessageId(item.get("id"));
        row.setRole(item.get("role"));
        row.setContent(item.get("content"));
        row.setPart(part);
        row.setTs(parseTs(item.get("ts")));
        row.setTenantId(DEFAULT_TENANT);
        row.setArchivedAt(now);
        archiveMapper.insert(row);
    }

    /** 为快照 item 补齐稳定消息 ID（存量快照在下次写回时补全） */
    private static void ensureMessageId(Map<String, String> item) {
        String id = item.get("id");
        if (id == null || id.isBlank()) {
            item.put("id", newMessageId());
        }
    }

    /** 消息 ID：m-<32 位 hex>，写入快照 JSON 并随 Message metadata 读取 */
    private static String newMessageId() {
        return "m-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 序列化重试一次：transient 失败自愈，连续失败返回 null（调用方跳过写回并已记日志） */
    private String serializeWithRetry(String sessionId, List<Map<String, String>> items) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return objectMapper.writeValueAsString(items);
            } catch (JacksonException e) {
                if (attempt == 1) {
                    log.warn("序列化会话上下文失败（重试一次）sessionId={}: {}", sessionId, e.getMessage());
                } else {
                    log.error("序列化会话上下文失败（已重试仍失败，本轮上下文副本丢失）sessionId={}", sessionId, e);
                }
            }
        }
        return null;
    }

    /** 字符串 sessionId 转 Long（session 表主键为 BIGINT；非法格式返回 null 并告警） */
    private Long parseSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(sessionId.trim());
        } catch (NumberFormatException e) {
            log.warn("非法 sessionId 格式: '{}'，无法查询 session 表", sessionId);
            return null;
        }
    }

    /** 由 Spring AI Message 得到 role 字符串（system 角色提示词不入库：每次请求现组装，落库会造成角色错位） */
    private static String roleOf(Message message) {
        return message instanceof AssistantMessage ? "assistant" : "user";
    }
}
