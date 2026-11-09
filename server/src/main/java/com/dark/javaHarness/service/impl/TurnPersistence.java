package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.contract.model.Goal;
import com.dark.javaHarness.contract.model.SessionInfo;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.contract.model.GoalStatus;
import com.dark.javaHarness.exception.ResumeConflictException;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.service.GoalService;
import com.dark.javaHarness.contract.model.SessionMessageEntry;
import com.dark.javaHarness.contract.api.SessionService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 一轮聊天的会话状态与落库（自 {@link ChatServiceImpl} 拆出，超长类拆分 2026-11-05）：
 * 会话建档 / Agent 绑定 / 会话绑定 Agent 解析 / 成功轮写回 / 续跑前置校验。
 *
 * <p>读写路径的失败语义保持原状：Agent 绑定失败只告警不中断；会话 Agent 查询失败
 * 直接上抛（不回退 general）；写回只发生在成功轮。
 */
@Component
class TurnPersistence {

    private static final Logger log = LoggerFactory.getLogger(TurnPersistence.class);

    private final SessionService sessionService;
    private final AgentService agentService;
    private final GoalService goalService;

    TurnPersistence(SessionService sessionService, AgentService agentService, GoalService goalService) {
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.goalService = goalService;
    }

    /** 会话标识（sid + 是否新建） */
    record SessionCtx(String sid, boolean newSession) {
    }

    /** 同步入口建档：无 sessionId 时自动建档（session 表），会话名取首条提问 */
    SessionCtx ensureSession(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            return new SessionCtx(sessionService.createSession("anonymous", message), true);
        }
        return new SessionCtx(sessionId, false);
    }

    /** 流式入口建档：DB 操作隔离在 boundedElastic 上 */
    Mono<SessionCtx> ensureSessionReactive(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.fromCallable(() -> sessionService.createSession("anonymous", message))
                    .map(sid -> new SessionCtx(sid, true))
                    .subscribeOn(Schedulers.boundedElastic());
        }
        return Mono.just(new SessionCtx(sessionId, false));
    }

    /**
     * 会话内切换 Agent（副作用绑定）：失败只告警不中断，随后统一判定按会话绑定继续路由。
     * sync/stream 两入口共用。
     */
    boolean bindAgentQuietly(String sessionId, Long agentId) {
        try {
            sessionService.switchAgent(sessionId, agentId);
            return true;
        } catch (Exception e) {
            log.warn("[chat] 会话 Agent 同步失败（不影响本次路由）sid={} agentId={}: {}",
                    sessionId, agentId, safeMessage(e));
            return false;
        }
    }

    /**
     * 解析会话绑定的 Agent 名（session.agent_id → agent 表 agent_name）。
     * 会话不存在/未绑定/agent 行已删时回退默认 general。
     *
     * <p>查询失败（DB 不可用）向上抛而不是回退 general（优化审查 2026-11-03）：绑定了
     * wms 专员的会话静默跑通用 Agent，用户拿到的是「看起来正常但查不到数据」的回答，
     * 比请求失败更难定位。
     */
    String sessionAgentName(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return AgentConstants.DEFAULT_AGENT;
        }
        SessionInfo session = sessionService.getSession(sessionId);
        if (session == null || session.agentId() == null) {
            return AgentConstants.DEFAULT_AGENT;
        }
        return agentService.findAgentNameById(session.agentId().longValue())
                .orElse(AgentConstants.DEFAULT_AGENT);
    }

    /**
     * 同步路径写回（user 消息记录真实发送时刻，历史回显时间不失真）：仅成功轮写
     * （FAILED/取消不落上下文）；assistant 文本为回复后处理清洗后的文本。
     */
    void writeBackSucceededTurn(String sessionId, String message, Goal goal, String assistantReply, long userSentAt) {
        if (sessionId != null && !sessionId.isBlank() && goal.status() == GoalStatus.SUCCEEDED) {
            writeBackBoth(sessionId, message, assistantReply, userSentAt);
        }
    }

    /** 流式成功后写回会话记忆（assistant 完整回复已由流收集；user 记录发送时刻） */
    void writeBackReply(String sessionId, String message, String assistantReply, long userSentAt) {
        if (sessionId != null && !sessionId.isBlank()) {
            writeBackBoth(sessionId, message, assistantReply, userSentAt);
        }
    }

    /** 写回公共体（sync/stream 共用）：一轮 user+assistant 一次批量落快照（一次锁/一次序列化）+ touch 会话 */
    private void writeBackBoth(String sessionId, String message, String assistantReply, long userSentAt) {
        sessionService.saveContextBatch(sessionId, List.of(
                new SessionMessageEntry(new UserMessage(message), userSentAt),
                new SessionMessageEntry(new AssistantMessage(assistantReply), System.currentTimeMillis())));
        sessionService.touchSession(sessionId, message);
    }

    /** 续跑前置校验：goal 必须存在、非 RUNNING、非 SUCCEEDED（违规按 400/409 抛出） */
    Goal requireResumable(String goalId) {
        if (goalId == null || goalId.isBlank()) {
            throw new IllegalArgumentException("goalId 不能为空");
        }
        Goal goal = goalService.get(goalId)
                .orElseThrow(() -> new IllegalArgumentException("目标不存在: " + goalId));
        if (goal.status() == GoalStatus.RUNNING) {
            throw new ResumeConflictException("该目标仍在执行中，无法续跑: " + goalId);
        }
        if (goal.status() == GoalStatus.SUCCEEDED) {
            // 已成功的 goal 再 resume 会把状态拉回 RUNNING（kill 进程后卡死），且检查点回放
            // 只是跳过所有节点重复吐最终结果——直接拒绝，避免误以为有新工作发生
            throw new ResumeConflictException("该任务已完成，无需续跑: " + goalId);
        }
        log.info("[resume] goal '{}' 续跑请求（原状态={}）", goal.id(), goal.status());
        return goal;
    }

    /**
     * 生成轮次标识 turn_id（32 位 hex UUID）：一条用户消息触发的完整处理一个，
     * 入口处在调用发起线程生成（纯内存操作，无锁无 IO，观测零主链路影响）。
     */
    static String newTurnId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 异常信息取安全短文案（message 为空时退回类名，避免日志出现 "null"） */
    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
