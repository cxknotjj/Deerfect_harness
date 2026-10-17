package com.dark.javaHarness.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.domain.dto.SessionMessagesView;
import com.dark.javaHarness.domain.entity.SessionEntity;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

/**
 * 会话服务：管理多轮会话记忆（session + session_messages 两张表）。
 *
 * 同时实现 Spring AI {@link ChatMemory} 接口：Agent 侧只读消费（get/loadContext），
 * 写入统一由 ChatService 在调用结束后负责（saveContext）——会话快照只存
 * user/assistant 两类角色，system 角色提示词不入库（避免角色错位）。
 */
public interface SessionService extends ChatMemory {

    /**
     * 创建新会话，会话名取首条提问（截断）。
     * @return sessionId（session 表自增主键的字符串形式）
     */
    String createSession(String creator, String firstQuestion);

    /**
     * 读取会话完整上下文，还原为 Spring AI Message 列表。
     */
    List<Message> loadContext(String sessionId);

    /**
     * 读取会话历史消息（时间顺序的 role/content/ts 视图，供前端回显）。
     * 数据源与 {@link #loadContext} 同一份上下文快照，仅做展示形态转换。
     * ts 为消息真实时刻（epoch 毫秒；user=发送时刻、assistant=完成时刻）；
     * 旧快照无时间记录为 null。
     *
     * @return 会话不存在或无历史时返回空列表
     */
    List<SessionMessagesView.Item> listMessages(String sessionId);

    /**
     * 追加保存单条会话消息（session_messages 与 session 一对一，该会话仅一行）。
     * 等价于 {@link #saveContext(String, Message, Long)} 且 ts 取当前时刻。
     */
    void saveContext(String sessionId, Message message);

    /**
     * 追加保存单条会话消息并记录时间戳：快照 item 携带 ts（epoch 毫秒字符串），
     * 供历史回显真实时间。user 消息传发送时刻、assistant 传完成时刻；ts 为 null 按当前时刻处理。
     */
    void saveContext(String sessionId, Message message, Long ts);

    /** 更新会话的最近一次提问 */
    void touchSession(String sessionId, String lastQuestion);

    /** 查询会话（软删除的不会返回） */
    SessionEntity getSession(String sessionId);

    /**
     * 会话内切换 Agent：校验 agentId 在 agent 表存在后，更新 session 表 agent_id。
     * 与当前值相同则跳过写库（幂等，聊天路径每条消息都带 agentId 时避免无谓更新）。
     *
     * @throws IllegalArgumentException sessionId 非法、会话不存在或 agentId 在 agent 表无记录
     */
    void switchAgent(String sessionId, Long agentId);

    /**
     * 分页查询会话（软删除的不会返回），按会话ID降序（最新在前）。
     * @param current 页码，从 1 开始
     * @param size    每页条数
     * @return MyBatis-Plus 分页结果（含总数与页数）
     */
    Page<SessionEntity> page(long current, long size);

    /**
     * 删除会话：软删 session 行 + 物理删该会话的上下文快照行 + 清 QQ 会话绑定行。
     * 不触碰调用日志（llm_call_log/tool_call_log）与异步目标（goal）。
     * 幂等：会话不存在或已删除时静默成功。
     */
    void deleteSession(String sessionId);
}