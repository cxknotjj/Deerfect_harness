package com.dark.javaHarness.agent;

/**
 * 子任务占位结果常量：失败 / 墙钟超时 / 预算超限跳过。
 *
 * <p>编排（graph）写入占位结果，进度播报（agent 的 BranchProgressListener）据此生成
 * 用户可见文案。放在 agent 包是为了避免 agent → graph 的反向依赖。
 */
public final class SubtaskResults {

    /** 预算超限跳过 */
    public static final String SKIPPED = "（预算超限跳过：编排 token 消费已达上限）";

    /** 子任务墙钟超时 */
    public static final String TIMEOUT_SKIPPED = "（子任务执行超时，已跳过）";

    /** 子任务执行失败（模型异常/重试耗尽/看门狗中止） */
    public static final String FAILED = "（子任务执行失败，已跳过）";

    private SubtaskResults() {
    }
}
