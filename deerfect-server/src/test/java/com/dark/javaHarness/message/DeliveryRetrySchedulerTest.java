package com.dark.javaHarness.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.DeliveryTask;
import com.dark.javaHarness.contract.spi.MessageSender;
import com.dark.javaHarness.contract.spi.MessageSender.DeliveryReceipt;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 重投调度单测：送达、退避、换跳、终态、单条异常不影响同批。
 *
 * <p>这条链的每个分支都对应一种真实故障，所以逐个断言：
 * 网络抖动要重试、配置错不能重试、一跳用尽要换路、链走完要留错误信息。
 */
@ExtendWith(MockitoExtension.class)
class DeliveryRetrySchedulerTest {

    @Mock
    private DeliveryOutboxService outbox;

    private MessageSender qq;
    private DeliveryRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        qq = mock(MessageSender.class);
        org.mockito.Mockito.lenient().when(qq.channel()).thenReturn("qq");
        scheduler = new DeliveryRetryScheduler(outbox, new MessageSenderRegistry(List.of(qq)),
                50, 60, 3, List.of(60L, 180L, 600L));
    }

    private static DeliveryTask task(long id, String chain, int chainIndex, int hopAttempts) {
        int chainLength = chain.split("\\|", -1).length;
        return new DeliveryTask(id, "schedule", "s1/g1", chain, chainIndex, "正文",
                hopAttempts, hopAttempts, chainLength);
    }

    private void claim(DeliveryTask... tasks) {
        when(outbox.claimDue(anyInt(), anyLong())).thenReturn(List.of(tasks));
    }

    @Test
    void success_marksSent() {
        claim(task(1L, "qq:1", 0, 1));
        when(qq.sendForReceipt(any(), any())).thenReturn(DeliveryReceipt.sent("m-1"));

        assertEquals(1, scheduler.deliverDue());
        verify(outbox).markSent(1L);
        verify(outbox, never()).scheduleRetry(anyLong(), any(), anyLong());
    }

    @Test
    void retryableUnderLimit_schedulesRetryWithFirstBackoff() {
        claim(task(1L, "qq:1", 0, 1));
        when(qq.sendForReceipt(any(), any())).thenReturn(DeliveryReceipt.failed("网络超时"));

        assertEquals(0, scheduler.deliverDue());
        verify(outbox).scheduleRetry(eq(1L), contains("网络超时"), eq(60L));
        verify(outbox, never()).advanceChain(any(), any());
    }

    @Test
    void retryableAtLimit_advancesChain() {
        claim(task(1L, "qq:1|email:a@b.com", 0, 3));
        when(qq.sendForReceipt(any(), any())).thenReturn(DeliveryReceipt.failed("网络超时"));

        assertEquals(0, scheduler.deliverDue());
        verify(outbox).advanceChain(any(), contains("网络超时"));
        verify(outbox, never()).scheduleRetry(anyLong(), any(), anyLong());
    }

    @Test
    void fatalFailure_marksDeadWithoutRetry() {
        claim(task(1L, "qq:1|email:a@b.com", 0, 1));
        when(qq.sendForReceipt(any(), any())).thenReturn(DeliveryReceipt.fatal("签名错"));

        assertEquals(0, scheduler.deliverDue());
        verify(outbox).markDead(1L, "签名错");
        verify(outbox, never()).scheduleRetry(anyLong(), any(), anyLong());
        verify(outbox, never()).advanceChain(any(), any());
    }

    @Test
    void lastHopExhausted_goesDead() {
        // 两跳都用已注册渠道：只注册了 qq，用 email 会被判「渠道未注册」提前终态
        claim(task(1L, "qq:1|qq:2", 1, 3));
        when(qq.sendForReceipt(any(), any())).thenReturn(DeliveryReceipt.failed("还是失败"));

        scheduler.deliverDue();

        // chainIndex=1 已是最后一跳，advanceChain 内部会置 DEAD
        verify(outbox).advanceChain(any(), contains("还是失败"));
    }

    @Test
    void claimEmpty_doesNothing() {
        when(outbox.claimDue(anyInt(), anyLong())).thenReturn(List.of());

        assertEquals(0, scheduler.deliverDue());
        verify(qq, never()).sendForReceipt(any(), any());
    }

    @Test
    void wrongChainIndex_marksDeadInsteadOfThrowing() {
        claim(task(1L, "qq:1", 5, 1));

        assertEquals(0, scheduler.deliverDue());
        verify(outbox).markDead(eq(1L), contains("链上取不到"));
    }

    @Test
    void segmentAt_outOfRange_returnsNull() {
        assertNull(DeliveryRetryScheduler.segmentAt("qq:1", 1));
        assertNull(DeliveryRetryScheduler.segmentAt("", 0));
        assertNull(DeliveryRetryScheduler.segmentAt("qq:1|email:a@b.com", -1));
        assertEquals("email:a@b.com", DeliveryRetryScheduler.segmentAt("qq:1|email:a@b.com", 1));
    }

    @Test
    void oneTaskThrows_othersStillDelivered() {
        when(outbox.claimDue(anyInt(), anyLong()))
                .thenReturn(List.of(task(1L, "qq:", 0, 1), task(2L, "qq:2", 0, 1)));
        when(qq.sendForReceipt(any(), any())).thenReturn(DeliveryReceipt.sent("m"));

        // "qq:" 缺 target → 注册表判为非法（不可重试），走 markDead；第二条正常送达
        assertEquals(1, scheduler.deliverDue());
        verify(outbox).markSent(2L);
    }
}
