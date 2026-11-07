package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.domain.ChatMessageMetadata;
import com.dark.javaHarness.view.SessionMessagesView;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.domain.entity.SessionMessageArchiveEntity;
import com.dark.javaHarness.domain.entity.SessionMessageEntity;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.mapper.SessionMessageArchiveMapper;
import com.dark.javaHarness.mapper.SessionMessageMapper;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.SessionBindingCleaner;
import com.dark.javaHarness.service.SessionMessageEntry;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.SessionSummaryPort;
import com.dark.javaHarness.service.SessionSnapshotItem;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话服务实现：基于 session + session_messages 两张表实现多轮会话记忆。
 *
 * 存储模型（上下文快照式，session_messages 与 session 一对一）：
 * - session：会话主表（名称/创建者/最近提问/软删除）
 * - session_messages：每个会话仅一行，content 字段以 JSON 形式存储该会话的
 *   【完整会话上下文】：
 *   [{"role":"user","content":"..."},{"role":"assistant","content":"..."},...]
 *   每条新消息追加进该行 content；读取时还原全部历史。
 *   只存 user/assistant 两类角色：system 角色提示词每次请求现组装，一旦落库，
 *   旧提示词会在后续轮次抢占上下文头部，导致角色错位。
 *
 * sessionId 使用 session 表自增主键的字符串形式，与 session_messages.session_id（varchar）对齐。
 *
 * <p>体量说明（principal §1，约 520 行不拆）：快照读写、裁剪归档、ChatMemory 适配三者共用
 * 同一份「单行 JSON 快照」存储模型——裁剪规则（保留条数/字符上限/配对保护）与读路径必须
 * 同步演进，拆开会让两条规则分处两个类而难以保持一致；并发写回的 FOR UPDATE 锁也必须与
 * 读-改-写同处一个事务边界内。若后续引入多行消息表，再按「存储 / 归档」切开。
 */
@Service
public class SessionServiceImpl implements SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionServiceImpl.class);

    /** 默认租户（未做多租户前统一使用） */
    private static final String DEFAULT_TENANT = "default";
    /** 快照行的角色标记：content 存的是全量上下文 JSON */
    private static final String ROLE_CONTEXT = "context";
    /** 默认 Agent 编号（Agent 尚无编号体系，先固定登记） */
    private static final int DEFAULT_AGENT_ID = 1;
    /** 会话名称最大长度（取首条提问截断） */
    private static final int SESSION_NAME_MAX = 100;
    /** 建档占位名（显式建档默认名）：写回时若名字仍为此值，以首条成功提问自动命名 */
    private static final String PLACEHOLDER_NAME = "新会话";
    /** 快照正文总字符上限（MEDIUMTEXT 16MB，按最坏 UTF-8 4 字节/码点预留余量；0 不限制） */
    private static final int SNAPSHOT_MAX_CHARS = 4_000_000;

    private final SessionMapper sessionMapper;
    private final SessionMessageMapper messageMapper;
    /** 快照裁剪转归档（recall_history 数据源） */
    private final SessionMessageArchiveMapper archiveMapper;
    private final ObjectMapper objectMapper;
    /** agent 表读取器：switchAgent 校验目标 agentId 存在性 */
    private final AgentConfigProvider agentConfigProvider;
    /** 渠道会话绑定清理端口：删除会话时清理渠道侧绑定行（如 QQ），渠道未启用时无实现 */
    private final ObjectProvider<SessionBindingCleaner> bindingCleaner;
    /** 长期记忆滚动摘要端口（P2-3）：读取/投递均由 SessionSummaryService 承载 */
    private final SessionSummaryPort summaryPort;
    /** 快照保留消息条数上限（超出丢弃最旧；0 = 不限制） */
    private final int snapshotMaxMessages;

    public SessionServiceImpl(SessionMapper sessionMapper,
                              SessionMessageMapper messageMapper,
                              SessionMessageArchiveMapper archiveMapper,
                              ObjectMapper objectMapper,
                              AgentConfigProvider agentConfigProvider,
                              ObjectProvider<SessionBindingCleaner> bindingCleaner,
                              SessionSummaryPort summaryPort,
                              @org.springframework.beans.factory.annotation.Value("${app.session.snapshot-max-messages:200}")
                              int snapshotMaxMessages) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.archiveMapper = archiveMapper;
        this.objectMapper = objectMapper;
        this.agentConfigProvider = agentConfigProvider;
        this.bindingCleaner = bindingCleaner;
        this.summaryPort = summaryPort;
        this.snapshotMaxMessages = snapshotMaxMessages;
    }

    /** 创建新会话（会话名取首条提问截断），返回自增主键的字符串形式 */
    @Override
    public String createSession(String creator, String firstQuestion) {
        SessionEntity session = new SessionEntity();
        session.setAgentId(DEFAULT_AGENT_ID);
        session.setSessionName(truncate(firstQuestion, SESSION_NAME_MAX));
        session.setCreator(creator == null || creator.isBlank() ? "anonymous" : creator);
        session.setLastQuestion(truncate(firstQuestion, 200));
        session.setIsDelete(0);
        // 画像提炼扫描依据：建档即活跃；profile_extracted_at 留空 = 从未提炼
        session.setLastActiveAt(LocalDateTime.now());
        sessionMapper.insert(session);
        log.info("创建会话 sessionId={}, name='{}'", session.getSessionId(), session.getSessionName());
        return String.valueOf(session.getSessionId());
    }

    /** 读取会话完整上下文，还原为 Spring AI Message 列表（无会话或解析失败返回空列表） */
    @Override
    public List<Message> loadContext(String sessionId) {
        List<Map<String, String>> items = readSnapshot(sessionId);
        List<Message> messages = new ArrayList<>(items.size());
        for (Map<String, String> item : items) {
            messages.add(toMessage(item.get("role"), item.get("content"), item.get("id")));
        }
        return messages;
    }

    /** 读取会话历史消息（复用上下文快照解析，转换为 role/content/ts 展示形态；旧快照无 ts 为 null） */
    @Override
    public List<SessionMessagesView.Item> listMessages(String sessionId) {
        List<SessionMessagesView.Item> items = new ArrayList<>();
        for (Map<String, String> item : readSnapshot(sessionId)) {
            items.add(new SessionMessagesView.Item(
                    item.get("role"), item.get("content"), parseTs(item.get("ts"))));
        }
        return items;
    }

    /** 读取该会话最新的上下文快照 item 列表（无会话/无行/解析失败返回空列表） */
    private List<Map<String, String>> readSnapshot(String sessionId) {
        return parseSnapshotItems(latestSnapshotRow(sessionId));
    }

    /**
     * 快照条目读侧视图（带 id 与 ts）。
     *
     * <p>与 {@link #listMessages} 共用同一份快照，仅多暴露条目 id——增量消费方
     * （画像提炼）用它做「上次处理到哪一条」的游标。
     */
    @Override
    public List<SessionSnapshotItem> snapshotItems(String sessionId) {
        List<SessionSnapshotItem> items = new ArrayList<>();
        for (Map<String, String> item : readSnapshot(sessionId)) {
            items.add(new SessionSnapshotItem(
                    item.get("id"), item.get("role"), item.get("content"), parseTs(item.get("ts"))));
        }
        return items;
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

    /** 追加保存单条会话消息到该会话唯一一行上下文（不存在则新建），ts 取当前时刻 */
    @Override
    @Transactional
    public void saveContext(String sessionId, Message message) {
        saveContextBatch(sessionId,
                List.of(new SessionMessageEntry(message, System.currentTimeMillis())));
    }

    /**
     * 追加保存单条会话消息并记录时间戳（ts 为消息真实时刻：user=发送、assistant=完成）。
     * 单条与批量共用同一条写入路径（单条即一条 entry 的批量）。
     */
    @Override
    @Transactional
    public void saveContext(String sessionId, Message message, Long ts) {
        saveContextBatch(sessionId, List.of(new SessionMessageEntry(message, ts)));
    }

    /**
     * 批量追加保存会话消息：一轮 user+assistant 一次落盘（一次行锁 / 一次快照读取 /
     * 一次序列化 / 一次 UPDATE）。逐条写回会把整份上下文 JSON 读改写两遍，长会话下
     * 写放大与锁持有时间都翻倍（优化审查 2026-11-03）。
     *
     * <p>并发安全（优化审查 2026-09-25 中危项）：先以 FOR UPDATE 锁会话行作为该会话上下文
     * 写回的互斥点，读-改-写全程串行——并发写同一会话（流式收尾 + QQ 同步、连续两轮竞速）
     * 不再互相覆盖丢消息。事务边界由 @Transactional 保证（FOR UPDATE 须在事务内才有意义），
     * 三个入口各自标注，避免自调用绕过代理导致 2 参路径无事务。
     *
     * <p>存在性判定与互斥锁合并为同一次查询（优化审查 2026-11-03）：锁查询本身就返回行，
     * 行不存在/已软删即跳过写回，不再额外 SELECT 一次会话行。
     */
    @Override
    @Transactional
    public void saveContextBatch(String sessionId, List<SessionMessageEntry> entries) {
        if (sessionId == null || sessionId.isBlank() || entries == null || entries.isEmpty()) {
            return;
        }
        // 防复活守卫：会话已软删/不存在时跳过写回，避免已删会话在流收尾时重建孤儿快照
        Long sid = parseSessionId(sessionId);
        if (sid == null || lockSessionRow(sid) == null) {
            log.info("会话不存在或已删除，跳过上下文写回 sessionId={}", sessionId);
            return;
        }
        // 读取已有上下文，按顺序追加本批消息
        SessionMessageEntity existing = latestSnapshotRow(sessionId);
        List<Map<String, String>> items = new ArrayList<>(parseSnapshotItems(existing));
        // 存量 item 无 id：在此轮写回时补齐，保证后续裁剪归档有稳定定位符
        items.forEach(SessionServiceImpl::ensureMessageId);
        for (SessionMessageEntry entry : entries) {
            if (entry != null && entry.message() != null) {
                items.add(snapshotItem(entry.message(), entry.ts()));
            }
        }
        TrimResult trimmed = trimSnapshot(items);

        String json = serializeWithRetry(sessionId, items);
        if (json == null) {
            return; // 两次序列化均失败：本轮上下文副本丢失（回复已送达），仅记错误日志
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

    /** 会话行锁（FOR UPDATE）：同一会话的上下文写回以该锁串行化；返回行本身（null=不存在/已软删）；须在 @Transactional 内调用 */
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

    /** 序列化重试一次： transient 失败自愈，连续失败返回 null（调用方跳过写回并已记日志） */
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

    /** 更新会话的最近一次提问；名字仍为建档占位「新会话」时以本条提问自动命名（首条成功消息生效） */
    @Override
    public void touchSession(String sessionId, String lastQuestion) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return;
        }
        UpdateWrapper<SessionEntity> uw = new UpdateWrapper<>();
        uw.eq("session_id", sid)
                .set("last_question", truncate(lastQuestion, 200))
                // 活跃时间同步刷新（画像提取扫描依据）
                .set("last_active_at", LocalDateTime.now())
                // 占位名自动命名：仅当名字仍是建档占位「新会话」时以本条提问改名（IF 原子判断不回读，
                // 显式命名的会话不受影响；{0}/{1} 参数占位防注入）
                .setSql("session_name = IF(session_name = {0}, {1}, session_name)",
                        PLACEHOLDER_NAME, truncate(lastQuestion, SESSION_NAME_MAX));
        sessionMapper.update(null, uw);
    }

    /** 查询会话（非法或空 sessionId 返回 null） */
    @Override
    public SessionEntity getSession(String sessionId) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return null;
        }
        QueryWrapper<SessionEntity> qw = new QueryWrapper<>();
        qw.eq("session_id", sid);
        return sessionMapper.selectOne(qw);
    }

    /** 会话内切换 Agent：agentId 存在性校验 + 会话存在性校验后更新 agent_id（与当前值相同跳过写库） */
    @Override
    public void switchAgent(String sessionId, Long agentId) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            throw new IllegalArgumentException("非法 sessionId: " + sessionId);
        }
        if (agentId == null) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        SessionEntity session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        String agentName = agentConfigProvider.findAgentNameById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("agent 不存在: " + agentId));
        if (session.getAgentId() != null && session.getAgentId().longValue() == agentId) {
            log.debug("会话已绑定该 Agent，跳过更新 sessionId={} agentId={}", sessionId, agentId);
            return;
        }
        UpdateWrapper<SessionEntity> uw = new UpdateWrapper<>();
        uw.eq("session_id", sid)
                .set("agent_id", agentId);
        sessionMapper.update(null, uw);
        log.info("会话切换 Agent sessionId={} -> agentId={}('{}')", sessionId, agentId, agentName);
    }

    /** 分页查询会话（软删除的不会返回），按会话ID降序（最新在前） */
    @Override
    public Page<SessionEntity> page(long current, long size) {
        QueryWrapper<SessionEntity> qw = new QueryWrapper<>();
        qw.orderByDesc("session_id");
        return sessionMapper.selectPage(new Page<>(Math.max(current, 1), Math.max(size, 1)), qw);
    }

    /**
     * 删除会话：软删 session 行（@TableLogic 使 deleteById 转为 UPDATE is_delete=1，
     * 已删行不匹配天然幂等）+ 物理删该会话的上下文快照行（残留会让 GET messages
     * 仍可读到已删会话内容）+ 经 {@link SessionBindingCleaner} 端口清渠道侧绑定行
     * （避免 QQ 用户带着旧 id 重建孤儿快照；渠道未启用时无实现静默跳过）。
     * 不触碰调用日志与 goal 表。
     */
    @Override
    public void deleteSession(String sessionId) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return;
        }
        sessionMapper.deleteById(sid);
        QueryWrapper<SessionMessageEntity> qw = new QueryWrapper<>();
        qw.eq("session_id", String.valueOf(sid));
        messageMapper.delete(qw);
        SessionBindingCleaner cleaner = bindingCleaner.getIfAvailable();
        if (cleaner != null) {
            cleaner.deleteBySessionId(sid);
        }
        log.info("删除会话 sessionId={}", sid);
    }

    /* ---------------- Spring AI ChatMemory 接口实现（包装现有逻辑） ---------------- */

    /** 长期记忆摘要读取（P2-3）：委托 SessionSummaryService，失败在实现侧降级为 null */
    @Override
    public String summaryOf(String sessionId) {
        return summaryPort.summaryOf(sessionId);
    }

    /** 长期记忆摘要投递（P2-3）：异步合并被裁剪的历史，不阻塞调用方 */
    @Override
    public void requestSummary(String sessionId, List<Message> droppedMessages) {
        summaryPort.requestSummary(sessionId, droppedMessages);
    }

    /** ChatMemory.get：按会话ID读取历史，等价于 {@link #loadContext}。 */
    @Override
    public List<Message> get(String conversationId) {
        return loadContext(conversationId);
    }

    /** ChatMemory.add：追加一组消息到指定会话，等价于逐个 {@link #saveContext}。 */
    @Override
    public void add(String conversationId, List<Message> messages) {
        for (Message m : messages) {
            saveContext(conversationId, m);
        }
    }

    /** ChatMemory.clear：清空指定会话的历史上下文（删除 session_messages 该会话行）。 */
    @Override
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        QueryWrapper<SessionMessageEntity> qw = new QueryWrapper<>();
        qw.eq("session_id", conversationId);
        messageMapper.delete(qw);
        log.info("清空会话上下文 sessionId={}", conversationId);
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

    /**
     * 由 role 字符串还原 Spring AI Message（会话快照只存 user/assistant，未知角色按 user 还原）；
     * 消息 ID 放进 metadata，供上下文裁剪生成归档引用、recall_history 定位。
     */
    private Message toMessage(String role, String content, String messageId) {
        Map<String, Object> metadata = messageId == null || messageId.isBlank()
                ? Map.of()
                : Map.of(ChatMessageMetadata.MESSAGE_ID, messageId);
        return "assistant".equals(role)
                ? AssistantMessage.builder().content(content).properties(metadata).build()
                : UserMessage.builder().text(content).metadata(metadata).build();
    }

    /** 由 Spring AI Message 得到 role 字符串（system 角色提示词不入库：每次请求现组装，落库会造成角色错位） */
    private static String roleOf(Message message) {
        return message instanceof AssistantMessage ? "assistant" : "user";
    }

    /** 字符串截断到指定最大长度（null 返回空串） */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}