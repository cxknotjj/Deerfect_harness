package com.dark.javaHarness.contract.model;

/**
 * 随一轮对话提交的二进制附件（目前只用于图片）。
 *
 * <p>{@code data} 是原始字节，不是 base64：渠道下载完直接交上来，由执行侧决定怎么编码。
 * HTTP 入口经 Jackson 反序列化时按 base64 处理，两种来源最终都是同一份字节。
 *
 * @param mimeType 媒体类型，如 {@code image/png}；决定模型侧的编码方式
 * @param data     原始字节，不得为空
 */
public record ChatAttachment(String mimeType, byte[] data) {

    public ChatAttachment {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType 不能为空");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("附件内容不能为空");
        }
    }
}
