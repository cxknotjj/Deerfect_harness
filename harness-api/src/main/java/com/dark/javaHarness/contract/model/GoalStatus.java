package com.dark.javaHarness.contract.model;

/**
 * Goal 生命周期状态。
 *
 * <p>{@link #ACTIVE} 与 {@link #RUNNING} 的区别：ACTIVE 是「用户声明的跨轮长期目标」，
 * 不会被执行链消耗、不参与看门狗的超时判定，只用于每轮对话的上下文注入；
 * RUNNING 是「这一次执行正在进行」。两者混用会让长期目标被 watchdog 判死。
 */
public enum GoalStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, ACTIVE
}
