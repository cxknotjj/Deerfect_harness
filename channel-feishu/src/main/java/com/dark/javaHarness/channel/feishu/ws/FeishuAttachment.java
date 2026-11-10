package com.dark.javaHarness.channel.feishu.ws;

/**
 * 入站消息携带的资源引用。
 *
 * <p>事件里只有资源键，字节要单独下载；下载放在处理线程而不是 SDK 回调线程里
 * （见 {@link FeishuInboundService} 的 3 秒应答约束）。
 *
 * @param fileKey 飞书资源键：图片是 {@code image_key}，文件是 {@code file_key}
 */
public record FeishuAttachment(String fileKey) {
}
