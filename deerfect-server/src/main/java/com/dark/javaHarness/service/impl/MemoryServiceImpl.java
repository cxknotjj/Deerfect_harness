package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.domain.Memory;
import com.dark.javaHarness.domain.entity.MemoryEntity;
import com.dark.javaHarness.mapper.MemoryMapper;
import com.dark.javaHarness.service.MemoryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 显式记忆实现。
 *
 * <p><b>同 key 覆盖</b>：写新记忆时先软删同 key 的有效旧条目。用户说「记住，我换车牌了」
 * 时不该出现两条互相矛盾的事实；软删而不是物理删，保留「什么时候改过」的痕迹。
 *
 * <p><b>过期与软删都在查询侧过滤</b>：注入、list、按关键词定位都只认「未删 + 未过期」，
 * 状态在读取时按 now 计算而不落库——避免依赖定时任务刷新状态列。
 *
 * <p><b>为什么不建唯一索引</b>：软删后同 key 重建会撞唯一键；同 key 唯一性由应用层
 * 「先软删再插入」保证，历史版本仍可审计。
 */
@Service
public class MemoryServiceImpl implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceImpl.class);

    /** 当前单用户固定租户（与 SessionServiceImpl 同口径） */
    private static final String DEFAULT_TENANT = "default";

    /** 允许的来源值：对话中明示 / 命令行 / 管理接口 */
    private static final Set<String> SOURCES = Set.of("user", "cli", "manual");

    private final MemoryMapper mapper;

    public MemoryServiceImpl(MemoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Memory remember(String content, String key, String source, String sessionId, LocalDateTime expiresAt) {
        String text = normalizeContent(content);
        String normalizedKey = normalizeKey(key);
        String normalizedSource = normalizeSource(source);
        LocalDateTime now = LocalDateTime.now();

        if (normalizedKey != null) {
            int replaced = softDeleteByKey(normalizedKey, now);
            if (replaced > 0) {
                log.info("[memory] key='{}' 已有 {} 条有效记忆，按最新覆盖", normalizedKey, replaced);
            }
        }

        MemoryEntity row = new MemoryEntity();
        row.setTenantId(DEFAULT_TENANT);
        row.setMemoryKey(normalizedKey);
        row.setContent(text);
        row.setSource(normalizedSource);
        row.setSessionId(parseSessionId(sessionId));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(expiresAt);
        mapper.insert(row);
        log.info("[memory] 已记住 id={} key={} source={}", row.getId(), normalizedKey, normalizedSource);
        return toDomain(row, now);
    }

    @Override
    public List<Memory> list(Memory.Status status, int limit) {
        Memory.Status target = status == null ? Memory.Status.ACTIVE : status;
        int cap = limit <= 0 ? MAX_INJECTION_ITEMS : Math.min(limit, 200);
        LocalDateTime now = LocalDateTime.now();
        return mapper.selectList(statusQuery(target, now).last("LIMIT " + cap)).stream()
                .map(row -> toDomain(row, now))
                .toList();
    }

    @Override
    public List<Memory> activeForInjection() {
        return list(Memory.Status.ACTIVE, MAX_INJECTION_ITEMS);
    }

    @Override
    public Optional<Memory> findActive(long id) {
        MemoryEntity row = mapper.selectById(id);
        if (row == null || !DEFAULT_TENANT.equals(row.getTenantId())) {
            return Optional.empty();
        }
        Memory memory = toDomain(row, LocalDateTime.now());
        return memory.status() == Memory.Status.ACTIVE ? Optional.of(memory) : Optional.empty();
    }

    @Override
    public List<Memory> findActiveByKeyword(String keyword) {
        String kw = keyword == null ? "" : keyword.strip();
        if (kw.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        QueryWrapper<MemoryEntity> query = new QueryWrapper<MemoryEntity>()
                .eq("tenant_id", DEFAULT_TENANT)
                .isNull("deleted_at")
                .and(w -> w.like("memory_key", kw).or().like("content", kw))
                .orderByDesc("id")
                .last("LIMIT " + MAX_KEYWORD_HITS);
        return mapper.selectList(query).stream()
                .map(row -> toDomain(row, now))
                .filter(m -> m.status() == Memory.Status.ACTIVE)
                .toList();
    }

    @Override
    public int countActiveByKey(String key) {
        String normalizedKey = normalizeKey(key);
        if (normalizedKey == null) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        QueryWrapper<MemoryEntity> query = new QueryWrapper<MemoryEntity>()
                .eq("tenant_id", DEFAULT_TENANT)
                .eq("memory_key", normalizedKey)
                .isNull("deleted_at")
                .and(w -> w.isNull("expires_at").or().gt("expires_at", now));
        Long count = mapper.selectCount(query);
        return count == null ? 0 : count.intValue();
    }

    @Override
    public boolean forget(long id) {
        return softDelete(new UpdateWrapper<MemoryEntity>().eq("id", id), LocalDateTime.now()) > 0;
    }

    @Override
    public int forgetByKey(String key) {
        String normalizedKey = normalizeKey(key);
        if (normalizedKey == null) {
            return 0;
        }
        return softDeleteByKey(normalizedKey, LocalDateTime.now());
    }

    /** 按状态拼查询条件：状态在读取时按 now 判定，不在库里维护状态列 */
    private static QueryWrapper<MemoryEntity> statusQuery(Memory.Status status, LocalDateTime now) {
        QueryWrapper<MemoryEntity> query = new QueryWrapper<MemoryEntity>()
                .eq("tenant_id", DEFAULT_TENANT);
        switch (status) {
            case ACTIVE -> query.isNull("deleted_at")
                    .and(w -> w.isNull("expires_at").or().gt("expires_at", now));
            case EXPIRED -> query.isNull("deleted_at")
                    .isNotNull("expires_at")
                    .le("expires_at", now);
            case DELETED -> query.isNotNull("deleted_at");
        }
        return query.orderByDesc("id");
    }

    private int softDeleteByKey(String key, LocalDateTime now) {
        return softDelete(new UpdateWrapper<MemoryEntity>().eq("memory_key", key), now);
    }

    private int softDelete(UpdateWrapper<MemoryEntity> where, LocalDateTime now) {
        where.eq("tenant_id", DEFAULT_TENANT)
                .isNull("deleted_at")
                .set("deleted_at", now)
                .set("updated_at", now);
        return mapper.update(null, where);
    }

    private static Memory toDomain(MemoryEntity row, LocalDateTime now) {
        return new Memory(row.getId(), row.getMemoryKey(), row.getContent(), row.getSource(),
                row.getSessionId() == null ? null : String.valueOf(row.getSessionId()),
                row.getCreatedAt(), row.getExpiresAt(), statusOf(row, now));
    }

    private static Memory.Status statusOf(MemoryEntity row, LocalDateTime now) {
        if (row.getDeletedAt() != null) {
            return Memory.Status.DELETED;
        }
        if (row.getExpiresAt() != null && !row.getExpiresAt().isAfter(now)) {
            return Memory.Status.EXPIRED;
        }
        return Memory.Status.ACTIVE;
    }

    private static String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        String text = content.strip();
        if (text.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(
                    "记忆内容不能超过 " + MAX_CONTENT_CHARS + " 字（当前 " + text.length() + " 字）");
        }
        return text;
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String text = key.strip();
        if (text.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException("记忆标签不能超过 " + MAX_KEY_CHARS + " 字（当前 " + text.length() + " 字）");
        }
        return text;
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "user";
        }
        String text = source.strip().toLowerCase(java.util.Locale.ROOT);
        if (!SOURCES.contains(text)) {
            throw new IllegalArgumentException("记忆来源只支持 user / cli / manual");
        }
        return text;
    }

    private static Long parseSessionId(String sessionId) {
        try {
            return sessionId == null || sessionId.isBlank() ? null : Long.valueOf(sessionId.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
