package com.dark.javaHarness.config.security;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录态登记簿：内存维护「登录凭证 → 过期时间」映射，仅回答「请求是否来自已登录的浏览器」。
 *
 * <p>与聊天会话无关——聊天历史在 MySQL（session_messages），此处不含任何业务数据。
 * 取内存而非 DB/签名 token 的取舍（单实例 + 单用户场景）：
 * <ul>
 *   <li>过滤器在每个 /api 请求前执行，内存查询零 I/O；DB 方案为每请求加一次往返，不值</li>
 *   <li>重启清空 = 全员重新登录一次，代价是单用户输一次口令；换来可立即全量吊销（revokeAll）</li>
 *   <li>多实例部署属 P3 立项范围，届时与 Redis/分布式锁同批评估，不单独为其上存储</li>
 * </ul>
 * 线程安全：ConcurrentHashMap.compute 原子完成「校验 + 滑动续期 + 过期即删」。
 */
public class LoginStateStore {

    private final long ttlMillis;
    private final ConcurrentHashMap<String, Long> expiryByKey = new ConcurrentHashMap<>();

    public LoginStateStore(Duration ttl) {
        this.ttlMillis = ttl.toMillis();
    }

    /** 签发新登录凭证；顺带全量清扫过期项，避免长期运行下死亡凭证堆积 */
    public String create() {
        purge();
        String token = UUID.randomUUID().toString();
        expiryByKey.put(token, now() + ttlMillis);
        return token;
    }

    /** 校验凭证：有效则滑动续期（活跃访问把过期时间推后一个 TTL），失效（含不存在）返回 false 并惰性移除 */
    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long expiry = expiryByKey.compute(token, (k, v) -> v == null || v <= now() ? null : now() + ttlMillis);
        return expiry != null;
    }

    /** 单独吊销（登出） */
    public void revoke(String token) {
        if (token != null) {
            expiryByKey.remove(token);
        }
    }

    /** 全量吊销（改口令/应急踢下线），返回吊销条数 */
    public int revokeAll() {
        int n = expiryByKey.size();
        expiryByKey.clear();
        return n;
    }

    /** 当前登记条数（测试与运维观测用） */
    public int size() {
        return expiryByKey.size();
    }

    private void purge() {
        long now = now();
        expiryByKey.values().removeIf(expiry -> expiry <= now);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
