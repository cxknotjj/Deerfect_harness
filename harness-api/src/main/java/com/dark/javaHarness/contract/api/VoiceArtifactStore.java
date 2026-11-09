package com.dark.javaHarness.contract.api;

import java.util.Optional;

/**
 * 语音工件库：工具只负责把文本合成为音频并放入这里，渠道按 flag 取走发送。
 *
 * <p>跨模块契约放在 harness-api，避免 core 依赖 channel。工件是短生命周期内存数据，
 * 不落盘、不对外暴露；`findByTurn` 供 core 生成本轮 flag，`take` 由渠道一次性消费。
 */
public interface VoiceArtifactStore {

    /** 存入音频并返回工件 id；同一 turnId 重复存入时覆盖旧工件。 */
    String put(String turnId, String text, byte[] audio, String format);

    /** 按本轮 turnId 查找（只读，不删除）；供 ChatService 追加 voice flag。 */
    Optional<VoiceArtifact> findByTurn(String turnId);

    /** 按 id 取走并删除；渠道发送语音前调用。 */
    Optional<VoiceArtifact> take(String id);

    record VoiceArtifact(String id, String turnId, String text,
                         byte[] audio, String format, long createdAt) {
    }
}
