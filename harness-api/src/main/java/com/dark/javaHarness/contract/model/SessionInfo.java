package com.dark.javaHarness.contract.model;

import java.time.LocalDateTime;

/**
 * 会话的对外视图（{@code session} 表的一个子集）。
 *
 * <p>契约层不返回 MyBatis 实体：数据库列名、逻辑删除标记、画像提炼游标这些都是持久化细节，
 * 一旦出现在端口签名里，改列名就会打断渠道与 CLI 的编译。此记录只保留调用方真正需要的字段。
 *
 * @param id            会话主键（{@code session.session_id}）
 * @param name          会话名（默认占位「新会话」，首条提问后自动改名）
 * @param creator       创建者标识，形如 {@code qq:123456} / {@code feishu:ou_xxx} / {@code cli}
 * @param lastQuestion  最近一次提问
 * @param agentId       已绑定的 Agent 主键；未绑定为 null
 * @param lastActiveAt  最近活跃时刻；旧数据可能为 null
 */
public record SessionInfo(
        Long id,
        String name,
        String creator,
        String lastQuestion,
        Integer agentId,
        LocalDateTime lastActiveAt) {
}
