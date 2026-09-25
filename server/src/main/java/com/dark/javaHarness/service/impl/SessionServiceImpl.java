package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.domain.dto.SessionMessagesView;
import com.dark.javaHarness.domain.entity.OneBotSessionBinding;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.domain.entity.SessionMessageEntity;
import com.dark.javaHarness.mapper.OneBotSessionBindingMapper;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.mapper.SessionMessageMapper;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.SessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

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

    private final SessionMapper sessionMapper;
    private final SessionMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    /** agent 表读取器：switchAgent 校验目标 agentId 存在性 */
    private final AgentConfigProvider agentConfigProvider;
    /** QQ 会话绑定表：删除会话时清理绑定行，避免 QQ 用户带着旧 id 重建孤儿快照 */
    private final OneBotSessionBindingMapper bindingMapper;

    public SessionServiceImpl(SessionMapper sessionMapper,
                              SessionMessageMapper messageMapper,
                              ObjectMapper objectMapper,
                              AgentConfigProvider agentConfigProvider,
                              OneBotSessionBindingMapper bindingMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.agentConfigProvider = agentConfigProvider;
        this.bindingMapper = bindingMapper;
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
        // 画像提取扫描依据：建档即活跃；标记待提炼
        session.setLastActiveAt(LocalDateTime.now());
        session.setProfileExtracted(0);
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
            messages.add(toMessage(item.get("role"), item.get("content")));
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
        } catch (JsonProcessingException e) {
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
    public void saveContext(String sessionId, Message message) {
        saveContext(sessionId, message, System.currentTimeMillis());
    }

    /** 追加保存单条会话消息并记录时间戳（ts 为消息真实时刻：user=发送、assistant=完成；null 按当前处理） */
    @Override
    public void saveContext(String sessionId, Message message, Long ts) {
        if (sessionId == null || sessionId.isBlank() || message == null) {
            return;
        }
        // 防复活守卫：会话已软删/不存在时跳过写回，避免已删会话在流收尾时重建孤儿快照
        if (getSession(sessionId) == null) {
            log.info("会话不存在或已删除，跳过上下文写回 sessionId={}", sessionId);
            return;
        }
        // 读取已有上下文，追加本条消息
        SessionMessageEntity existing = latestSnapshotRow(sessionId);
        List<Map<String, String>> items = new ArrayList<>(parseSnapshotItems(existing));
        Map<String, String> item = new LinkedHashMap<>();
        item.put("role", roleOf(message));
        item.put("content", message.getText());
        if (ts != null) {
            // 时间戳以字符串形态入快照（保持 Map<String,String> 反序列化兼容）；仅展示用，loadContext 忽略
            item.put("ts", String.valueOf(ts));
        }
        items.add(item);

        String json;
        try {
            json = objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            log.error("序列化会话上下文失败 sessionId={}", sessionId, e);
            return;
        }
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
     * 仍可读到已删会话内容）+ 清 QQ 会话绑定行。不触碰调用日志与 goal 表。
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
        QueryWrapper<OneBotSessionBinding> bw = new QueryWrapper<>();
        bw.eq("session_id", sid);
        bindingMapper.delete(bw);
        log.info("删除会话 sessionId={}", sid);
    }

    /* ---------------- Spring AI ChatMemory 接口实现（包装现有逻辑） ---------------- */

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

    /** 由 role 字符串还原 Spring AI Message（会话快照只存 user/assistant，未知角色按 user 还原） */
    private Message toMessage(String role, String content) {
        return "assistant".equals(role) ? new AssistantMessage(content) : new UserMessage(content);
    }

    /** 由 Spring AI Message 得到 role 字符串（system 角色提示词不入库：每次请求现组装，落库会造成角色错位） */
    private String roleOf(Message message) {
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