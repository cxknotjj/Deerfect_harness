package com.dark.javaHarness.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.domain.DeliveryTask;
import com.dark.javaHarness.domain.entity.DeliveryOutboxEntity;
import com.dark.javaHarness.mapper.DeliveryOutboxMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * outbox 服务单测：入队、领取（发送前计数 + 条件更新）、结案、退避、链推进。
 *
 * <p>关键断言都在「领取」上：{@code attempts} 必须在发送**之前**递增，否则「发送中进程被杀」
 * 会永远不计次、无限重试。这是条件更新的唯一动机，与多实例无关。
 */
@ExtendWith(MockitoExtension.class)
class DeliveryOutboxServiceTest {

    @Mock
    private DeliveryOutboxMapper mapper;

    private DeliveryOutboxService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryOutboxService(mapper);
    }

    private static DeliveryOutboxEntity row(long id, String chain, int chainIndex, int attempts, int hopAttempts) {
        DeliveryOutboxEntity row = new DeliveryOutboxEntity();
        row.setId(id);
        row.setSource("schedule");
        row.setRefId("s1/g1");
        row.setChannelChain(chain);
        row.setChainIndex(chainIndex);
        row.setContent("正文");
        row.setStatus(DeliveryOutboxService.STATUS_PENDING);
        row.setAttempts(attempts);
        row.setHopAttempts(hopAttempts);
        return row;
    }

    private static DeliveryTask task(long id, String chain, int chainIndex, int attempts, int hopAttempts) {
        return new DeliveryTask(id, "schedule", "s1/g1", chain, chainIndex, "正文",
                attempts, hopAttempts, chain.split("\\|", -1).length);
    }

    @Test
    void enqueue_writesPendingRowReadyToClaim() {
        // 模拟 MyBatis-Plus 的主键回填：mock 不会自动写 id，而 enqueue 要拿它做日志与返回值
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, DeliveryOutboxEntity.class).setId(1L);
            return 1;
        }).when(mapper).insert(any(DeliveryOutboxEntity.class));

        long id = service.enqueue("schedule", "s1/g1", "qq:1|email:a@b.com", "正文");
        assertEquals(1L, id);

        ArgumentCaptor<DeliveryOutboxEntity> captor = ArgumentCaptor.forClass(DeliveryOutboxEntity.class);
        verify(mapper).insert(captor.capture());
        DeliveryOutboxEntity saved = captor.getValue();
        assertEquals(DeliveryOutboxService.STATUS_PENDING, saved.getStatus());
        assertEquals(0, saved.getChainIndex());
        assertEquals(0, saved.getAttempts());
        assertEquals(0, saved.getHopAttempts());
        assertTrue(saved.getNextRetryAt() != null, "入队即可领取，否则第一条要等一个扫描周期");
    }

    @Test
    void claimDue_incrementsBeforeSend_andLeasesRow() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, "qq:1|email:a@b.com", 0, 2, 1)));
        when(mapper.update(isNull(), any())).thenReturn(1);

        List<DeliveryTask> tasks = service.claimDue(10, 60);

        assertEquals(1, tasks.size());
        DeliveryTask task = tasks.get(0);
        assertEquals(3, task.attempts(), "总次数 = 已有 + 本次领取");
        assertEquals(2, task.hopAttempts(), "本跳次数 = 已有 + 本次领取，退避序列靠它选");
        assertEquals(2, task.chainLength(), "两跳链");

        String sqlSet = capturedUpdate().getSqlSet();
        assertTrue(sqlSet.contains("attempts = attempts + 1"), sqlSet);
        assertTrue(sqlSet.contains("hop_attempts = hop_attempts + 1"), sqlSet);
        assertTrue(sqlSet.contains("next_retry_at"), "领取时必须推后时间，否则同一行会被下一轮重复领");
    }

    @Test
    void claimDue_someoneElseClaimed_skipsRow() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, "qq:1", 0, 0, 0)));
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertTrue(service.claimDue(10, 60).isEmpty(),
                "条件更新改到 0 行说明已被领走，本轮不能重复发");
    }

    @Test
    void claimDue_scanFails_returnsEmptyWithoutThrowing() {
        when(mapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        assertTrue(service.claimDue(10, 60).isEmpty(), "扫描失败只跳过本轮，不能炸掉调度");
    }

    @Test
    void markSent_setsSentStatus() {
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertTrue(service.markSent(1L));
        String sqlSet = capturedUpdate().getSqlSet();
        assertTrue(sqlSet.contains("status"), sqlSet);
        assertTrue(sqlSet.contains("sent_at"), sqlSet);
        assertTrue(sqlSet.contains("next_retry_at = NULL"), "结案后不该再被领取：" + sqlSet);
    }

    @Test
    void scheduleRetry_keepsPendingAndPushesNextAttempt() {
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertTrue(service.scheduleRetry(1L, "网络超时", 60));
        String sqlSet = capturedUpdate().getSqlSet();
        assertTrue(sqlSet.contains("last_error"), sqlSet);
        assertTrue(sqlSet.contains("next_retry_at"), sqlSet);
        assertFalse(sqlSet.contains("status"), "退避只改时间，状态仍是 PENDING：" + sqlSet);
    }

    @Test
    void advanceChain_movesToNextHopAndResetsHopCount() {
        when(mapper.update(isNull(), any())).thenReturn(1);

        boolean advanced = service.advanceChain(task(1L, "qq:1|email:a@b.com", 0, 3, 3), "qq 不可达");

        assertTrue(advanced);
        String sqlSet = capturedUpdate().getSqlSet();
        assertTrue(sqlSet.contains("chain_index = chain_index + 1"), sqlSet);
        assertTrue(sqlSet.contains("hop_attempts"), "换跳必须归零，否则下一跳一开始就被判超限：" + sqlSet);
    }

    @Test
    void advanceChain_lastHop_marksDead() {
        when(mapper.update(isNull(), any())).thenReturn(1);

        boolean advanced = service.advanceChain(task(1L, "qq:1|email:a@b.com", 1, 6, 3), "都失败了");

        assertFalse(advanced, "最后一跳没有下一跳可换");
        String sqlSet = capturedUpdate().getSqlSet();
        assertTrue(sqlSet.contains("status"), sqlSet);
        assertTrue(sqlSet.contains("last_error"), sqlSet);
    }

    @Test
    void markDead_recordsReason() {
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertTrue(service.markDead(1L, "签名错"));
        String sqlSet = capturedUpdate().getSqlSet();
        assertTrue(sqlSet.contains("status"), sqlSet);
        assertTrue(sqlSet.contains("last_error"), sqlSet);
    }

    @Test
    void countByStatus_returnsZeroWhenMapperReturnsNull() {
        when(mapper.selectCount(any())).thenReturn(null);

        assertEquals(0, service.countByStatus(DeliveryOutboxService.STATUS_DEAD));
    }

    @Test
    void markSent_whenAlreadySettled_returnsFalse() {
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertFalse(service.markSent(1L), "状态已被别的路径改掉时不该重复结案");
        verify(mapper, never()).selectList(any());
    }

    /** 取最后一次 update 的 wrapper，并先渲染 SET 片段（参数惰性求值） */
    @SuppressWarnings("unchecked")
    private UpdateWrapper<DeliveryOutboxEntity> capturedUpdate() {
        ArgumentCaptor<Wrapper<DeliveryOutboxEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).update(isNull(), captor.capture());
        UpdateWrapper<DeliveryOutboxEntity> wrapper =
                (UpdateWrapper<DeliveryOutboxEntity>) captor.getAllValues().get(captor.getAllValues().size() - 1);
        wrapper.getSqlSet();
        wrapper.getSqlSegment();
        return wrapper;
    }

    // ---------- 长文分流 ----------

    /** 超阈值且有 email：把 email 提到链首——截断会丢信息，转邮件不丢 */
    @Test
    void enqueue_longText_prefersEmail() {
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, DeliveryOutboxEntity.class).setId(1L);
            return 1;
        }).when(mapper).insert(any(DeliveryOutboxEntity.class));

        service.enqueue("schedule", "s1/g1", "feishu:main|email:a@b.com", "x".repeat(8000));

        assertEquals("email:a@b.com|feishu:main", capturedInsert().getChannelChain());
    }

    @Test
    void enqueue_shortText_keepsOrder() {
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, DeliveryOutboxEntity.class).setId(1L);
            return 1;
        }).when(mapper).insert(any(DeliveryOutboxEntity.class));

        service.enqueue("schedule", "s1/g1", "feishu:main|email:a@b.com", "x".repeat(500));

        assertEquals("feishu:main|email:a@b.com", capturedInsert().getChannelChain());
    }

    @Test
    void enqueue_longTextWithoutEmail_keepsOrder() {
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, DeliveryOutboxEntity.class).setId(1L);
            return 1;
        }).when(mapper).insert(any(DeliveryOutboxEntity.class));

        service.enqueue("schedule", "s1/g1", "qq:1|feishu:main", "x".repeat(8000));

        assertEquals("qq:1|feishu:main", capturedInsert().getChannelChain(),
                "链上没有 email 时保持原序，由各渠道自己的上限处理");
    }

    private DeliveryOutboxEntity capturedInsert() {
        ArgumentCaptor<DeliveryOutboxEntity> captor = ArgumentCaptor.forClass(DeliveryOutboxEntity.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }
}
