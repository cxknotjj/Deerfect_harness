package com.dark.javaHarness.agent;

import com.dark.javaHarness.exception.ModelAuthException;
import com.dark.javaHarness.exception.ModelQuotaException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * LLM 错误分类纯函数（call/stream 两通道共用）：账户级硬错误的判定与转换收敛于此，
 * 消除两处重复的「matches → from 转换」逻辑，保证口径同源不漂移。
 */
final class LlmErrorClassifier {

    private LlmErrorClassifier() {
    }

    /**
     * 账户级硬错误转换（重试无意义，不重试直接向上传播）：
     * <ul>
     *   <li>余额不足/配额耗尽（402/403）→ {@link ModelQuotaException} 人话异常；
     *   <li>鉴权失败（401/invalid key）→ {@link ModelAuthException} 可执行指引异常。
     * </ul>
     * 均未命中返回 null（调用方按可重试错误走原路径：record 后上抛/重试判定）。
     */
    static RuntimeException translate(RuntimeException e, String model) {
        if (ModelQuotaException.matches(e)) {
            return ModelQuotaException.from(e, model);
        }
        if (ModelAuthException.matches(e)) {
            return ModelAuthException.from(e, model);
        }
        return null;
    }

    /**
     * 模型幻觉出不存在的工具调用（工具名不在回调列表中，Spring AI 执行阶段抛出）。
     *
     * <p>轻量态兼容（延迟加载开启时）：所有已分配工具名均已注册（轻量 callback），已注册但
     * 未展开的工具被直接调用时走 {@link com.dark.javaHarness.prompt.ToolLazyManager} 的引导文本
     * （正常工具结果，不抛异常），只有真·未注册名才触发本降级——两分支不冲突。
     */
    static boolean isUnknownToolCall(RuntimeException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("No ToolCallback found for tool name");
    }

    /**
     * 客户端请求错误（4xx，除 408）判定：请求内容/鉴权被供应商拒绝，连接本身健康——
     * 丢池重建无助益且波及同池在途请求（管道 doOnError 丢池决策用）。
     *
     * <p>匹配两种载体形态（沿 cause 链）：WebClient 层的 {@link WebClientResponseException}
     * （流式链路直接上抛）与 Spring AI 包装的 {@link NonTransientAiException}
     * （message 以 "4xx " 开头）。408 请求超时例外：常为池内陈旧连接的表征，按连接问题
     * 对待（丢池）。
     */
    static boolean isClientRequestError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException wex) {
                int status = wex.getStatusCode().value();
                if (status >= 400 && status < 500 && status != 408) {
                    return true;
                }
            }
            if (t instanceof NonTransientAiException nt) {
                Integer status = leadingStatus(nt.getMessage());
                if (status != null && status >= 400 && status < 500 && status != 408) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break; // 自引用环防护
            }
        }
        return false;
    }

    /** 解析 "4xx ..." 形态 message 的前导状态码（非该形态返回 null） */
    private static Integer leadingStatus(String msg) {
        if (msg == null || msg.length() < 4 || msg.charAt(3) != ' ') {
            return null;
        }
        try {
            return Integer.parseInt(msg.substring(0, 3));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
