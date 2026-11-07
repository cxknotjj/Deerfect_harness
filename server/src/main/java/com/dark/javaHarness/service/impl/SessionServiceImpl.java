package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.domain.ChatMessageMetadata;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.SessionBindingCleaner;
import com.dark.javaHarness.service.SessionMessageEntry;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.SessionSnapshotItem;
import com.dark.javaHarness.service.SessionSummaryPort;
import com.dark.javaHarness.view.SessionMessagesView;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话服务实现：会话生命周期（建档 / 自动命名 / 切换 Agent / 分页 / 软删）+ ChatMemory 适配。
 *
 * <p>存储模型（上下文快照式，session_messages 与 session 一对一）：
 * - session：会话主表（名称/创建者/最近提问/软激活时间/软删除）
 * - session_messages：每个会话仅一行，content 以 JSON 存该会话的完整上下文；
 *   只存 user/assistant 两类角色（system 提示词每次请求现组装，落库会导致角色错位）。
 *
 * <p><b>快照读写已抽到 {@link SessionSnapshotStore}</b>（2026-11-07）：此前本类同时承担
 * 建档、快照读写、裁剪归档、摘要触发、查询五类职责，581 行。抽取时把「快照读 + 追加写 +
 * 裁剪规则 + 归档 + 序列化重试 + 写回行锁」<b>整体</b>搬到 Store——原类头担心「读路径与裁剪
 * 规则分家会不一致」，这个风险靠「不拆它们」解决，而不是靠「不拆类」；拆开后它们仍在同一个
 * 类里同步演进，本类只留下不关心快照怎么存的会话元数据。
 *
 * <p>事务边界留在本类：{@link #saveContextBatch} 等写入口标 {@code @Transactional}，
 * Store 内部的行锁才有意义。
 */
@Service
public class SessionServiceImpl implements SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionServiceImpl.class);

    /** 默认 Agent 编号（Agent 尚无编号体系，先固定登记） */
    private static final int DEFAULT_AGENT_ID = 1;
    /** 会话名称最大长度（取首条提问截断） */
    private static final int SESSION_NAME_MAX = 100;
    /** 建档占位名（显式建档默认名）：写回时若名字仍为此值，以首条成功提问自动命名 */
    private static final String PLACEHOLDER_NAME = "新会话";

    private final SessionMapper sessionMapper;
    /** 上下文快照的读 / 追加写 / 裁剪归档（会话元数据之外的存储细节都在这里） */
    private final SessionSnapshotStore snapshotStore;
    /** agent 表读取器：switchAgent 校验目标 agentId 存在性 */
    private final AgentConfigProvider agentConfigProvider;
    /** 渠道会话绑定清理端口：删除会话时清理渠道侧绑定行（如 QQ），渠道未启用时无实现 */
    private final ObjectProvider<SessionBindingCleaner> bindingCleaner;
    /** 长期记忆滚动摘要端口（P2-3）：读取/投递均由 SessionSummaryService 承载 */
    private final SessionSummaryPort summaryPort;

    public SessionServiceImpl(SessionMapper sessionMapper,
                              SessionSnapshotStore snapshotStore,
                              AgentConfigProvider agentConfigProvider,
                              ObjectProvider<SessionBindingCleaner> bindingCleaner,
                              SessionSummaryPort summaryPort) {
        this.sessionMapper = sessionMapper;
        this.snapshotStore = snapshotStore;
        this.agentConfigProvider = agentConfigProvider;
        this.bindingCleaner = bindingCleaner;
        this.summaryPort = summaryPort;
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
        List<Map<String, String>> items = snapshotStore.readItems(sessionId);
        List<Message> messages = new ArrayList<>(items.size());
        for (Map<String, String> item : items) {
            messages.add(toMessage(item.get("role"), item.get("content"), item.get("id")));
        }
        return messages;
    }

    /** 读取会话历史消息（快照条目的 role/content/ts 展示形态；旧快照无 ts 为 null） */
    @Override
    public List<SessionMessagesView.Item> listMessages(String sessionId) {
        List<SessionMessagesView.Item> items = new ArrayList<>();
        for (SessionSnapshotItem item : snapshotStore.items(sessionId)) {
            items.add(new SessionMessagesView.Item(item.role(), item.content(), item.ts()));
        }
        return items;
    }

    /**
     * 快照条目读侧视图（带 id 与 ts）。
     *
     * <p>与 {@link #listMessages} 共用同一份快照，仅多暴露条目 id——增量消费方
     * （画像提炼）用它做「上次处理到哪一条」的游标。
     */
    @Override
    public List<SessionSnapshotItem> snapshotItems(String sessionId) {
        return snapshotStore.items(sessionId);
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
     * 批量追加保存会话消息：一轮 user+assistant 一次落盘。
     *
     * <p>事务边界在此（三个入口各自标注，避免自调用绕过代理导致 2 参路径无事务）；
     * 快照的读-改-写、FOR UPDATE 行锁、裁剪与归档都在 {@link SessionSnapshotStore#appendBatch}，
     * 必须与本事务同生共死——先归档再写快照，任一失败一起回滚，不产生「快照已丢、归档没落」。
     */
    @Override
    @Transactional
    public void saveContextBatch(String sessionId, List<SessionMessageEntry> entries) {
        snapshotStore.appendBatch(sessionId, entries);
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
        snapshotStore.deleteBySessionId(String.valueOf(sid));
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
        snapshotStore.deleteBySessionId(conversationId);
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

    /** 字符串截断到指定最大长度（null 返回空串） */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
