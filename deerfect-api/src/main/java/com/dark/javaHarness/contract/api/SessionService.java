package com.dark.javaHarness.contract.api;

import com.dark.javaHarness.contract.model.SessionInfo;
import com.dark.javaHarness.contract.model.SessionMessageEntry;
import com.dark.javaHarness.contract.model.SessionSnapshotItem;
import com.dark.javaHarness.view.PageResult;
import com.dark.javaHarness.view.SessionMessagesView;
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
public interface SessionService extends ChatMemory, SessionSummaryPort {

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
     * 读取会话快照条目（含稳定 id），供增量消费方定位游标。
     *
     * <p>与 {@link #loadContext} 同一份数据、同一顺序；区别只在携带 id 与 ts——
     * 画像提炼要回答「上次处理到哪一条」，仅凭正文无法在裁剪后重新定位。
     *
     * @return 会话不存在或无历史时返回空列表
     */
    List<SessionSnapshotItem> snapshotItems(String sessionId);

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

    /**
     * 批量追加保存会话消息：一次行锁、一次快照读取、一次序列化、一次 UPDATE。
     *
     * <p>一轮对话的用户消息与助手回复应经此一次写入——逐条调用
     * {@link #saveContext(String, Message, Long)} 会把整份上下文 JSON 读改写两遍，
     * 长会话下写放大与锁持有时间都翻倍。
     *
     * @param entries 待追加消息（按顺序落快照，各自携带真实时刻）
     */
    void saveContextBatch(String sessionId, List<SessionMessageEntry> entries);

    /** 更新会话的最近一次提问 */
    void touchSession(String sessionId, String lastQuestion);

    /**
     * 查询会话（软删除的不会返回）。
     *
     * @return 会话视图；sessionId 非法或会话不存在时返回 <b>null</b>
     */
    SessionInfo getSession(String sessionId);

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
     * @return 分页结果；页数由调用方按 {@code total / size} 自行计算
     */
    PageResult<SessionInfo> page(long current, long size);

    /**
     * 删除会话：软删 session 行 + 物理删该会话的上下文快照行 + 清 QQ 会话绑定行。
     * 不触碰调用日志（llm_call_log/tool_call_log）与异步目标（goal）。
     * 幂等：会话不存在或已删除时静默成功。
     */
    void deleteSession(String sessionId);
}