package com.dark.javaHarness.state;

/**
 * 多 Agent 编排状态键：引擎、节点实现与执行体共用的唯一事实来源。
 * 独立成类是为了打断 {@code graph.engine} 与 {@code graph} 的循环依赖。
 */
public final class OrchestrationStateKeys {

    public static final String K_OBJECTIVE = "objective";
    public static final String K_SESSION_ID = "sessionId";
    /** 轨迹键：轮次/调用链标识（引擎从 Goal 注入；编排内 5 次调用共用） */
    public static final String K_TURN_ID = "turnId";
    public static final String K_TRACE_ID = "traceId";
    /** 来源渠道键：system prompt 的信道规范段据此生成（Goal 注入；旧检查点缺键按空处理） */
    public static final String K_CHANNEL = "channel";
    /** 轨迹键：lead 调用的 span_id（lead 节点发起调用前生成写入；subtask/aggregate 读出作 parent_span） */
    public static final String K_LEAD_SPAN = "leadSpanId";
    public static final String K_SUBTASK_COUNT = "subtaskCount";
    public static final String K_SUBTASK_PREFIX = "subtask_";
    public static final String K_SUBTASK_AGENT_PREFIX = "subtaskAgent_";
    /** 任务书键：lead 出口写入（已按预算截断），subtask 节点读出作 user 文本；旧 checkpoint 缺键 orElse(null) 安全 */
    public static final String K_SUBTASK_BRIEF_PREFIX = "subtaskBrief_";
    /** 工具包键：lead 出口写入包名 CSV（空名单写空串），subtask 节点读出逐包解析包定义；旧 checkpoint 缺键 orElse("") 与空串同口径=无包 */
    public static final String K_SUBTASK_PACKS_PREFIX = "subtaskPacks_";
    public static final String K_RESULT_PREFIX = "result_";
    public static final String K_FINAL = "final";

    /** 子任务节点名前缀 */
    public static final String SUBTASK_NODE_PREFIX = "subtask-";

    private OrchestrationStateKeys() {
    }
}
