package com.dark.javaHarness.memory;

import com.dark.javaHarness.domain.Memory;
import com.dark.javaHarness.service.MemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式记忆的仓储层集成测试：真实 MySQL + Flyway 建表 + MyBatis-Plus 生成的 SQL。
 *
 * <p>与 {@code MemoryServiceImplTest} 的分工：那边用 mock mapper 验业务分支，SQL 与条件拼装
 * 全被替身吞掉；这里真的落库，专门验「过滤条件写对了没」——{@code deleted_at IS NULL}、
 * {@code expires_at > now}、同 key 覆盖后旧行是否真的被软删。这两类问题在纯 mock 下都是绿的。
 *
 * <p>数据隔离：类级 {@code @Transactional} 让每个用例结束后回滚；key 再拼随机后缀，
 * 避免与本机已有记忆或并行执行互相干扰。
 */
@SpringBootTest
@Transactional
class MemoryRepositoryIntegrationTest {

    @Autowired
    private MemoryService memoryService;

    /** 唯一标签：同 key 断言不受库里已有行影响 */
    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void remember_writesRowAndAppearsInInjection() {
        Memory saved = memoryService.remember("用户的车牌是沪A12345", uniqueKey("车牌"), "user", "it-session", null);

        assertTrue(saved.id() > 0, "插入后应回填自增主键");
        assertEquals(Memory.Status.ACTIVE, saved.status());
        assertTrue(saved.createdAt() != null, "created_at 应由数据库/实体写入");

        assertTrue(ids(memoryService.activeForInjection()).contains(saved.id()),
                "新写入的记忆应出现在注入列表（走 deleted_at IS NULL AND 未过期的真实 SQL）");
    }

    @Test
    void remember_sameKey_softDeletesPreviousRow() {
        String key = uniqueKey("技术栈");
        Memory first = memoryService.remember("用户的技术栈是 Java", key, "user", null, null);
        Memory second = memoryService.remember("用户的技术栈是 Go", key, "user", null, null);

        assertNotEquals(first.id(), second.id(), "同 key 应新增一行，而不是原地覆盖");

        assertTrue(ids(memoryService.list(Memory.Status.DELETED, 50)).contains(first.id()),
                "旧行应被软删（deleted_at 非空），DELETED 状态可审计到");
        assertFalse(ids(memoryService.list(Memory.Status.ACTIVE, 50)).contains(first.id()),
                "旧行不应再算有效");
        assertTrue(ids(memoryService.list(Memory.Status.ACTIVE, 50)).contains(second.id()),
                "新行应有效");
    }

    @Test
    void forget_softDeletesAndHidesFromActive() {
        Memory saved = memoryService.remember("临时密码 1234", uniqueKey("临时"), "user", null, null);

        assertTrue(memoryService.forget(saved.id()), "首次忘记应更新到一行");
        assertFalse(memoryService.forget(saved.id()), "重复忘记返回 false（已软删，不再更新）");

        assertTrue(memoryService.findActive(saved.id()).isEmpty(), "已删记忆按 id 查不到");
        assertFalse(ids(memoryService.activeForInjection()).contains(saved.id()), "已删记忆不再注入");
        assertTrue(ids(memoryService.list(Memory.Status.DELETED, 50)).contains(saved.id()),
                "DELETED 状态仍可审计到该行");
    }

    @Test
    void expiredMemory_isExcludedFromInjectionButListedAsExpired() {
        Memory saved = memoryService.remember("过期的临时凭证", uniqueKey("过期"), "user", null,
                LocalDateTime.now().minusMinutes(1));

        assertEquals(Memory.Status.EXPIRED, saved.status(), "状态在读取时按 now 判定");
        assertFalse(ids(memoryService.activeForInjection()).contains(saved.id()), "过期记忆不注入");
        assertTrue(ids(memoryService.list(Memory.Status.EXPIRED, 50)).contains(saved.id()),
                "EXPIRED 状态能查到");
        assertFalse(ids(memoryService.list(Memory.Status.ACTIVE, 50)).contains(saved.id()),
                "过期记忆不属于 ACTIVE");
    }

    @Test
    void futureExpiry_staysActive() {
        Memory saved = memoryService.remember("明天过期的提醒", uniqueKey("未来"), "user", null,
                LocalDateTime.now().plusDays(1));

        assertEquals(Memory.Status.ACTIVE, saved.status());
        assertTrue(ids(memoryService.activeForInjection()).contains(saved.id()),
                "未到期的记忆应继续注入");
    }

    @Test
    void countActiveByKey_reflectsRealRows() {
        String key = uniqueKey("计数");
        memoryService.remember("第一条", key, "user", null, null);
        assertEquals(1, memoryService.countActiveByKey(key));

        memoryService.remember("第二条", key, "user", null, null);
        assertEquals(1, memoryService.countActiveByKey(key), "同 key 覆盖后有效行仍为 1");
    }

    @Test
    void forgetByKey_removesAllActiveRowsWithSameKey() {
        String key = uniqueKey("按key删");
        memoryService.remember("a", key, "user", null, null);
        memoryService.remember("b", key, "user", null, null);   // 覆盖前一条

        assertEquals(1, memoryService.forgetByKey(key), "覆盖后只剩一条有效行可删");
        assertEquals(0, memoryService.countActiveByKey(key));
    }

    @Test
    void findActiveByKeyword_matchesContentAndKey() {
        String keyword = "kw" + UUID.randomUUID().toString().substring(0, 6);
        Memory byContent = memoryService.remember("包含关键字 " + keyword + " 的正文", null, "user", null, null);
        Memory byKey = memoryService.remember("另一条", keyword, "user", null, null);

        List<Long> hits = ids(memoryService.findActiveByKeyword(keyword));
        assertTrue(hits.contains(byContent.id()), "应能按正文模糊匹配");
        assertTrue(hits.contains(byKey.id()), "应能按标签模糊匹配");
    }

    private static List<Long> ids(List<Memory> memories) {
        return memories.stream().map(Memory::id).toList();
    }
}
