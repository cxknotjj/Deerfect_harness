package com.dark.javaHarness.exception;

import com.openai.errors.OpenAIServiceException;

/**
 * 模型供应商账户级硬错误（余额不足 / 配额耗尽 / 访问被拒）。
 *
 * <p>这类错误重试无意义（厂商端账户状态问题，非瞬时故障），应在调用链最内层
 * 立即识别并转换为携带「哪个模型、什么问题、用户怎么办」的人话异常向上传播——
 * 编排路径降级为进度行展示，HTTP 路径由 {@link GlobalExceptionHandler} 统一映射 502。
 */
public class ModelQuotaException extends RuntimeException {

    /** 涉及的模型名（可为 null：调用方未显式指定） */
    private final String model;
    /** 厂商返回的 HTTP 状态码 */
    private final int httpStatus;

    public ModelQuotaException(String model, int httpStatus, String message) {
        super(message);
        this.model = model;
        this.httpStatus = httpStatus;
    }

    public String model() {
        return model;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 判定异常链中是否包含账户级硬错误（402 余额不足 / 403 配额耗尽或访问被拒）。
     *
     * <p>Spring AI 2.0 经 OpenAI SDK 抛出 {@link OpenAIServiceException}（按 statusCode 判定）；
     * 403 需额外匹配配额类关键词（FreeTier / quota），避免把普通权限拒绝误报成余额问题。
     */
    public static boolean matches(Throwable e) {
        OpenAIServiceException svc = findServiceException(e);
        if (svc == null) {
            return false;
        }
        if (svc.statusCode() == 402) {
            return true;
        }
        String msg = svc.getMessage();
        return svc.statusCode() == 403 && msg != null
                && (lowerContains(msg, "quota") || lowerContains(msg, "freetier"));
    }

    /** 从异常（含 cause 链）构造人话的账户错误异常；仅限 matches() 为 true 的异常调用 */
    public static ModelQuotaException from(Throwable e, String model) {
        OpenAIServiceException svc = findServiceException(e);
        int status = svc == null ? 0 : svc.statusCode();
        String hint;
        if (status == 402) {
            hint = "账户余额不足，请充值或更换模型";
        } else {
            hint = "配额耗尽或访问被拒，请充值、调整免费额度设置或更换模型";
        }
        String detail = svc == null || svc.getMessage() == null ? "" : brief(svc.getMessage());
        return new ModelQuotaException(model, status,
                "模型 '" + (model == null ? "默认" : model) + "' 调用失败（HTTP " + status
                        + "）：" + hint + (detail.isBlank() ? "" : "｜" + detail));
    }

    /* ---------- 内部工具 ---------- */

    /** 沿 cause 链查找 OpenAI SDK 的服务异常 */
    private static OpenAIServiceException findServiceException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OpenAIServiceException svc) {
                return svc;
            }
            if (t.getCause() == t) {
                break; // 自引用环防护
            }
        }
        return null;
    }

    /** 截取厂商响应中的人类可读片段（去掉 SDK 的 "<status>: " 前缀，原文太长只留前 120 字符） */
    private static String brief(String raw) {
        String body = raw;
        int sep = raw.indexOf(": ");
        if (sep > 0 && sep <= 4 && raw.substring(0, sep).matches("\\d{3}")) {
            body = raw.substring(sep + 2).trim();
        }
        return body.length() > 120 ? body.substring(0, 120) + "…" : body;
    }

    private static boolean lowerContains(String haystack, String needle) {
        return haystack.toLowerCase().contains(needle);
    }
}
