package com.dark.javaHarness.agent;

import java.util.concurrent.CancellationException;

/**
 * 取消语义词汇表（路径 A/B 与编排节点共用）：客户端断连中止在途请求的统一异常口径。
 * 取消不是可重试错误，是断连语义——各通道捕获后需重新抛出同语义异常时经
 * {@link #cancelException()} 工厂保持消息一致（llm_call_log.error_msg 检索依赖该文案）。
 */
final class CallCancellation {

    private CallCancellation() {
    }

    /** 取消异常消息（llm_call_log.error_msg 检索用）：客户端断连中止在途请求 */
    static final String CANCELLED_MSG = "client-cancelled: 客户端断连，中止在途请求";

    /** 取消异常工厂（包级共用：编排节点捕获后需重新抛出同语义异常） */
    static CancellationException cancelException() {
        return new CancellationException(CANCELLED_MSG);
    }
}
