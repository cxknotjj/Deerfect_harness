package com.dark.javaHarness.contract.api;

import java.util.Optional;

/**
 * 文件工件库：工具负责把沙箱里的产物读出来放这里，渠道按 flag 取走上传发送。
 *
 * <p>为什么走「内存中转」而不是让渠道自己去沙箱取：渠道只认识 HTTP 与飞书 API，
 * 一旦让它依赖沙箱，渠道模块就被绑死在 Docker 环境上；而且沙箱文件路径对渠道没有意义。
 * 这层中转让「谁产出」与「谁发送」彻底解耦，代价是要有大小上限与 TTL。
 *
 * <p>与 {@link VoiceArtifactStore} 同构：`findByTurn` 供 core 生成 flag，`take` 由渠道一次性消费。
 */
public interface FileArtifactStore {

    /** 存入文件并返回工件 id；同一 turnId 重复存入时覆盖旧工件 */
    String put(String turnId, String fileName, byte[] content);

    /** 按本轮 turnId 查找（只读，不删除）；供 core 追加 file flag */
    Optional<FileArtifact> findByTurn(String turnId);

    /** 按 id 取走并删除；渠道上传前调用 */
    Optional<FileArtifact> take(String id);

    record FileArtifact(String id, String turnId, String fileName, byte[] content, long createdAt) {
    }
}
