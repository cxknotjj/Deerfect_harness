package com.dark.wms.client;

/**
 * WMS 调用异常：message 为面向模型/用户的中文可读分类提示
 * （鉴权失败/参数错误/WMS 不可达/请求超时/业务失败等）。
 *
 * <p>工具层捕获后以结构化错误文本返回，不向上抛崩——MCP 会话保持存活
 * （对齐 spec「WMS 宕机不炸会话」场景）。
 */
public class WmsApiException extends RuntimeException {

    public WmsApiException(String message) {
        super(message);
    }

    public WmsApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
