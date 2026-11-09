package com.dark.javaHarness.controller;

import com.dark.javaHarness.contract.model.Goal;
import com.dark.javaHarness.contract.model.SessionInfo;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.service.GoalService;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.view.ActiveGoalRequest;
import com.dark.javaHarness.view.AgentsView;
import com.dark.javaHarness.view.GoalView;
import com.dark.javaHarness.view.GoalsView;
import com.dark.javaHarness.view.SessionAgentView;
import com.dark.javaHarness.view.SessionCreatedView;
import com.dark.javaHarness.view.SessionMessagesView;
import com.dark.javaHarness.view.SessionPageView;
import com.dark.javaHarness.view.SubmitView;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Harness 编排管理接口（表现层，纯转发）。
 */
@RestController
@RequestMapping("/api/harness")
public class HarnessController {

    private final AgentService agentService;
    private final GoalService goalService;
    private final SessionService sessionService;

    public HarnessController(AgentService agentService, GoalService goalService, SessionService sessionService) {
        this.agentService = agentService;
        this.goalService = goalService;
        this.sessionService = sessionService;
    }

    /** 列出已注册的 Agent */
    @GetMapping("/agents")
    public AgentsView agents() {
        return new AgentsView(agentService.agentItems());
    }

    /** 分页列出全部会话 */
    @GetMapping("/sessions")
    public SessionPageView sessions(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size) {
        return sessionPageView(sessionService.page(page, size));
    }

    /**
     * 新建空会话：立即建档返回 sessionId，后续聊天请求携带该 id 延续上下文。
     * 会话名默认占位「新会话」，最近提问由首条消息的写回流程更新。
     */
    @PostMapping("/sessions")
    public SessionCreatedView createSession(@RequestParam(defaultValue = "新会话") String name) {
        String sessionId = sessionService.createSession("cli", name);
        return new SessionCreatedView(sessionId, name);
    }

    /**
     * 会话内切换 Agent：更新 session 表 agent_id（SIMPLE 判定后由该 Agent 直答；
     * COMPLEX 编排失败降级重答的落点）。后续聊天请求仍统一经 RouteJudge 判定。
     * agentId 不存在/会话不存在抛 IllegalArgumentException（全局处理器映射 400）。
     */
    @PostMapping("/sessions/{sessionId}/agent")
    public SessionAgentView switchAgent(@PathVariable String sessionId, @RequestParam Long agentId) {
        sessionService.switchAgent(sessionId, agentId);
        String agentName = agentService.findAgentNameById(agentId).orElse(null);
        return new SessionAgentView(sessionId, agentId, agentName);
    }

    /**
     * 读取指定会话的历史消息：切回旧会话时回显对话（时间顺序的 role/content 列表）。
     * 会话不存在或无历史返回空列表（非错误：前端空窗即为空会话）。
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public SessionMessagesView sessionMessages(@PathVariable String sessionId) {
        return new SessionMessagesView(sessionId, sessionService.listMessages(sessionId));
    }

    /**
     * 删除会话：软删 session + 清上下文快照 + 清 QQ 绑定（幂等，会话不存在也成功）。
     * 调用日志与 goal 保留不动。成功返回空体 200。
     */
    @DeleteMapping("/sessions/{sessionId}")
    public void deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
    }

    /** 提交一个目标给指定 Agent 异步执行 */
    @PostMapping("/submit")
    public SubmitView submit(@RequestParam String agent,
                             @RequestParam String objective) {
        Goal goal = agentService.submit(agent, objective);
        return new SubmitView(goal.id(), goal.status().name());
    }

    /** 查询单个目标状态 */
    @GetMapping("/goals/{id}")
    public GoalView goal(@PathVariable String id) {
        Goal goal = goalService.get(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到目标: " + id));
        return goalView(goal);
    }

    /**
     * 登记跨轮长期目标（不进入执行链）：会话后续每轮对话都会在 system 里看到它。
     *
     * <p>与 POST /submit 的区别：submit 是「现在跑一次」，本端点是「记住要长期推进这件事」。
     */
    @PostMapping("/goals/active")
    public GoalView createActiveGoal(@RequestBody ActiveGoalRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (request.objective() == null || request.objective().isBlank()) {
            throw new IllegalArgumentException("objective 不能为空");
        }
        return goalView(goalService.createActive(request.objective(), request.sessionId()));
    }

    /** 查询会话当前活跃的长期目标（无目标返回空列表） */
    @GetMapping("/goals/active")
    public GoalsView activeGoals(@RequestParam String sessionId) {
        return new GoalsView(goalService.activeBySession(sessionId)
                .map(goal -> List.of(goalView(goal)))
                .orElse(List.of()));
    }

    /** 标记长期目标完成（只接受 ACTIVE 状态；已结束的传进来会 400） */
    @PostMapping("/goals/{id}/complete")
    public void completeActiveGoal(@PathVariable String id) {
        if (!goalService.completeActive(id)) {
            throw new IllegalArgumentException("目标不存在或不是活跃状态: " + id);
        }
    }

    /** 查询全部目标 */
    @GetMapping("/goals")
    public GoalsView goals() {
        return new GoalsView(goalService.all().stream().map(HarnessController::goalView).toList());
    }

    // ---- 视图装配（原 shared DTO 静态工厂收编至此：DTO 保持纯 record，实体/分页依赖留在 server） ----

    private static GoalView goalView(Goal goal) {
        return new GoalView(goal.id(), goal.objective(), goal.status().name(), goal.summary());
    }

    private static SessionPageView sessionPageView(com.dark.javaHarness.view.PageResult<SessionInfo> p) {
        return new SessionPageView(
                p.page(),
                p.size(),
                p.total(),
                p.size() <= 0 ? 0 : (p.total() + p.size() - 1) / p.size(),
                p.list().stream().map(HarnessController::sessionItem).toList());
    }

    private static SessionPageView.Item sessionItem(SessionInfo s) {
        java.time.LocalDateTime active = s.lastActiveAt();
        return new SessionPageView.Item(
                s.id() == null ? null : String.valueOf(s.id()),
                s.name(),
                s.creator(),
                s.lastQuestion(),
                active == null ? null : active.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
    }
}