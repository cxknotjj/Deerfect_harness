package com.dark.javaHarness.cli;

/**
 * 一次 CLI 进程内的会话选择状态（自 {@link ChatCli} 拆出，超长类拆分 2026-11-05）：
 * 命令分发（切换 Agent / 路径 / 续跑目标）与回合执行（记住服务端返回的会话 ID）
 * 共享同一份可变状态。
 */
final class ChatSessionState {

    /** 会话ID：首轮为空（由服务端自动建档），从首次响应中获取后复用，实现多轮记忆 */
    private String sessionId;

    /**
     * 当前选中的 Agent ID：null 表示交由服务端「主 Agent 前置判断」分流
     * （SIMPLE → general 单模型；COMPLEX → multi-agent 编排并推送进度事件）。
     * 注意：一旦非空（如默认带 1），服务端会绕过 RouteJudge 直接路由，
     * 永远走单 Agent 且无任何 progress 事件——所以默认必须保持 null。
     */
    private Long agentId;

    /** 显式路径：null/auto=自动判定，agentic / graph 为显式指定 */
    private String routing;

    /**
     * 当前会话最近一次复杂编排的 goalId（/resume 无参时的续跑目标）：
     * 编排流首的 goal 进度事件到达即记录（不等回合成功——CLI 断开前也已记录）；
     * 并持久化到 ~/.javaHarness_resume_state，CLI 重启后恢复（会话匹配才生效）。
     */
    private String lastOrchestratedGoalId;

    String sessionId() {
        return sessionId;
    }

    void sessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    Long agentId() {
        return agentId;
    }

    void agentId(Long agentId) {
        this.agentId = agentId;
    }

    String routing() {
        return routing;
    }

    void routing(String routing) {
        this.routing = routing;
    }

    String lastOrchestratedGoalId() {
        return lastOrchestratedGoalId;
    }

    void lastOrchestratedGoalId(String lastOrchestratedGoalId) {
        this.lastOrchestratedGoalId = lastOrchestratedGoalId;
    }
}
