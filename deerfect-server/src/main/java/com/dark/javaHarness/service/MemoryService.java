package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.Memory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 显式记忆服务：用户明说「记住」的事实的增删查。
 *
 * <p>与画像（{@code UserProfileService}）的分工：画像是模型从对话里推断的软偏好，
 * 可被下一次提炼整段改写；显式记忆是用户声明的事实，只有用户能删。两者注入时
 * 分段呈现，显式记忆在前，冲突以显式记忆为准。
 */
public interface MemoryService {

    /** 单条记忆正文字符上限（超出拒绝而不是静默截断：被截断的记忆等于记错） */
    int MAX_CONTENT_CHARS = 1000;

    /** 短标签字符上限 */
    int MAX_KEY_CHARS = 64;

    /** 单次注入的条数上限（记忆量小，全量注入；超过按最新截断） */
    int MAX_INJECTION_ITEMS = 20;

    /** 单次注入的字符上限（防长记忆挤占本轮上下文） */
    int MAX_INJECTION_CHARS = 1600;

    /** 关键词检索的候选上限（forget 多命中时要让用户确认，不能一次列几百条） */
    int MAX_KEYWORD_HITS = 20;

    /**
     * 记住一条事实。
     *
     * @param content   正文（必填）
     * @param key       可选短标签；同 key 的有效旧记忆会被软删（以最新为准）
     * @param source    来源：user / cli / manual
     * @param sessionId 来源会话（可空）
     * @param expiresAt 过期时间（null=长期）
     */
    Memory remember(String content, String key, String source, String sessionId, LocalDateTime expiresAt);

    /**
     * 列出记忆。
     *
     * @param status 过滤状态（null 视为 ACTIVE）；DELETED 用于审计查看
     * @param limit  条数上限
     */
    List<Memory> list(Memory.Status status, int limit);

    /** 待注入的记忆：未删、未过期，按创建时间倒序（最多 {@link #MAX_INJECTION_ITEMS} 条） */
    List<Memory> activeForInjection();

    /** 按 id 查一条有效记忆（已删/过期返回空） */
    Optional<Memory> findActive(long id);

    /** 按关键词模糊匹配有效记忆（key 或 content 含关键词），供 forget 定位 */
    List<Memory> findActiveByKeyword(String keyword);

    /** 统计同 key 的有效记忆条数（remember 前调用，用于告知用户「旧条目已被替换」） */
    int countActiveByKey(String key);

    /** 按 id 软删；返回是否删到（已删/不存在返回 false） */
    boolean forget(long id);

    /** 按 key 精确软删全部有效记忆；返回删除条数 */
    int forgetByKey(String key);
}
