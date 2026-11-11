package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dark.javaHarness.contract.model.Goal;
import com.dark.javaHarness.view.Schedule;
import com.dark.javaHarness.domain.entity.ScheduleEntity;
import com.dark.javaHarness.mapper.ScheduleMapper;
import com.dark.javaHarness.message.MessageSenderRegistry;
import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.service.GoalService;
import com.dark.javaHarness.contract.spi.MessageSender;
import com.dark.javaHarness.view.AgentItemView;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 定时任务单测：创建期校验、到点派发、失败不卡调度器、无后续时间即停用。
 *
 * <p>不碰真实 cron 调度器：{@code dispatchDue} 是普通方法，直接调用即可验证「扫描 → 提交 →
 * 推进」这条链，时间由固定 cron 表达式推导。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleServiceImplTest {

    @Mock
    private ScheduleMapper mapper;

    @Mock
    private AgentService agentService;

    @Mock
    private GoalService goalService;

    @Mock
    private MessageSender qqSender;

    @Mock
    private com.dark.javaHarness.message.DeliveryOutboxService outbox;

    private ScheduleServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(qqSender.channel()).thenReturn("qq");
        // 改造后调度侧不再直发：推送只负责入队，发送由 outbox 的重投调度器完成
        service = new ScheduleServiceImpl(mapper, agentService, goalService,
                new MessageSenderRegistry(List.of(qqSender)), outbox);
    }

    private void agentExists() {
        when(agentService.agentItems()).thenReturn(List.of(new AgentItemView(1L, "general")));
    }

    private static ScheduleEntity entity(String id, String cron, LocalDateTime nextRunAt) {
        ScheduleEntity row = new ScheduleEntity();
        row.setId(id);
        row.setObjective("每日早报");
        row.setAgentName("general");
        row.setCron(cron);
        row.setEnabled(1);
        row.setNextRunAt(nextRunAt);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    @Test
    void create_invalidCron_rejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("早报", "general", "not-a-cron", null));

        assertTrue(e.getMessage().contains("cron"), "错误信息应指向 cron: " + e.getMessage());
        verify(mapper, never()).insert(any(ScheduleEntity.class));
    }

    /** 间隔小于扫描周期两倍会丢推送（上一轮结果被新一轮覆盖），创建期就拒掉而不是等运行期静默丢 */
    @Test
    void create_tooFrequentCron_rejected() {
        agentExists();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("心跳", "general", "*/10 * * * * *", null));

        assertTrue(e.getMessage().contains("间隔过短"), e.getMessage());
        verify(mapper, never()).insert(any(ScheduleEntity.class));
    }

    @Test
    void create_unknownAgent_rejected() {
        agentExists();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("早报", "nobody", "0 0 8 * * *", null));

        assertTrue(e.getMessage().contains("agent 不存在"), e.getMessage());
        verify(mapper, never()).insert(any(ScheduleEntity.class));
    }

    @Test
    void create_blankObjective_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("  ", "general", "0 0 8 * * *", null));
        verify(mapper, never()).insert(any(ScheduleEntity.class));
    }

    @Test
    void create_computesNextRunAndEnables() {
        agentExists();
        when(mapper.insert(any(ScheduleEntity.class))).thenReturn(1);

        Schedule created = service.create("每日早报", "general", "0 0 8 * * *", " qq:10001 ");

        assertNotNull(created.id());
        assertTrue(created.id().startsWith("sched-"));
        assertTrue(created.enabled());
        assertNotNull(created.nextRunAt());
        assertTrue(created.nextRunAt().isAfter(LocalDateTime.now().minusMinutes(1)));
        assertEquals("qq:10001", created.notifyChannel(), "推送目标应 trim 后落库");
        verify(mapper).insert(any(ScheduleEntity.class));
    }

    @Test
    void create_noNotifyChannel_storedAsNull() {
        agentExists();
        when(mapper.insert(any(ScheduleEntity.class))).thenReturn(1);

        Schedule created = service.create("早报", "general", "0 0 8 * * *", "  ");

        assertEquals(null, created.notifyChannel());
    }

    @Test
    void dispatchDue_noDueRows_noop() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service.dispatchDue());
        verify(agentService, never()).submit(any(), any());
    }

    /** 到点任务：提交给 goal 链路，并把 next_run_at 推进到下一个触发点 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dispatchDue_dueRow_submitsAndAdvances() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(1);
        ScheduleEntity row = entity("sched-1", "0 0 8 * * *", past);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(agentService.submit("general", "每日早报")).thenReturn(new Goal("goal-1", "每日早报"));

        assertEquals(1, service.dispatchDue());

        verify(agentService).submit("general", "每日早报");
        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        String set = captor.getValue().getSqlSet();
        assertTrue(set.contains("next_run_at"), "应推进 next_run_at: " + set);
        assertTrue(set.contains("last_goal_id"), "应记录本次 goal: " + set);
    }

    /** 提交失败（agent 被删 / 队列满）也必须推进，否则每 10 秒重扫同一条、日志刷屏且卡住队首 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dispatchDue_submitFails_stillAdvances() {
        ScheduleEntity row = entity("sched-2", "0 0 8 * * *", LocalDateTime.now().minusMinutes(1));
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(agentService.submit(any(), any())).thenThrow(new IllegalStateException("agent 已被删除"));

        assertEquals(1, service.dispatchDue());

        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("next_run_at"),
                "失败也要推进时间，避免死循环触发");
    }

    /** 2 月 30 日这类表达式永不触发：停用而不是空转 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dispatchDue_cronNeverFires_disables() {
        ScheduleEntity row = entity("sched-3", "0 0 0 30 2 *", LocalDateTime.now().minusMinutes(1));
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(agentService.submit(any(), any())).thenReturn(new Goal("goal-3", "x"));

        service.dispatchDue();

        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("enabled"),
                "无后续触发时间应停用: " + captor.getValue().getSqlSet());
    }

    /**
     * 回归：tick 必须先收集上一轮结果再派发。
     *
     * <p>dispatchDue 会覆盖 last_goal_id；若先派发再查，高频 cron（间隔 ≤ 扫描周期）下
     * 永远只看到刚创建、还在 PENDING 的那个 goal，notified_at 永远置不上（2026-11-06
     * 用每 10 秒一条的 cron 端到端复现）。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void tick_collectsBeforeDispatch_soHighFrequencyCronStillNotifies() {
        ScheduleEntity row = pendingNotify("s10");
        row.setLastGoalId("goal-old");
        // 第一次 selectList 是 collectPending（上一轮），第二次是 dispatchDue（无到期任务）
        when(mapper.selectList(any())).thenReturn(List.of(row), List.of());

        Goal done = new Goal("goal-old", "早报");
        done.succeed("上一轮的结果");
        when(goalService.get("goal-old")).thenReturn(Optional.of(done));

        service.tick();

        verify(outbox, org.mockito.Mockito.timeout(2_000))
                .enqueue(eq("schedule"), any(), eq("qq:10001"), contains("上一轮的结果"));
    }

    /** tick 是调度入口：派发同步做，推送甩到独立线程（慢渠道不拖住下一轮派发） */
    @Test
    void tick_dispatchesWithoutThrowing() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.tick());
        verify(mapper, org.mockito.Mockito.atLeastOnce()).selectList(any());
    }

    // ---------- 结果推送（P1-2） ----------

    private static ScheduleEntity pendingNotify(String id) {
        ScheduleEntity row = entity(id, "0 0 8 * * *", LocalDateTime.now().plusHours(1));
        row.setLastGoalId("goal-" + id);
        row.setNotifyChannel("qq:10001");
        return row;
    }

    /** goal 还在跑：不发送、不标记，下一轮再看 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void notifyFinished_goalRunning_waits() {
        ScheduleEntity row = pendingNotify("s1");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        Goal running = new Goal("goal-s1", "早报");
        running.markRunning();
        when(goalService.get("goal-s1")).thenReturn(Optional.of(running));

        assertEquals(0, service.notifyFinished());
        verify(outbox, never()).enqueue(any(), any(), any(), any());
        verify(mapper, never()).update(isNull(), any());
    }

    /** 终态：按 channel:target 送达，正文带任务目标、结果与摘要，并置 notified_at */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void notifyFinished_succeeded_sendsAndMarks() {
        ScheduleEntity row = pendingNotify("s2");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        Goal done = new Goal("goal-s2", "早报");
        done.succeed("昨天完成 3 个任务");
        when(goalService.get("goal-s2")).thenReturn(Optional.of(done));

        assertEquals(1, service.notifyFinished());

        ArgumentCaptor<String> chain = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(any(), any(), chain.capture(), text.capture());
        assertEquals("qq:10001", chain.getValue(), "整条链原样入队，由 outbox 逐跳解析与降级");
        assertTrue(text.getValue().contains("【定时任务】每日早报"), text.getValue());
        assertTrue(text.getValue().contains("结果：成功"), text.getValue());
        assertTrue(text.getValue().contains("昨天完成 3 个任务"), text.getValue());

        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("notified_at"), "应标记已通知");
    }

    /** goal 已被保留期清理：标记掉，否则永远停在待推送 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void notifyFinished_goalMissing_marksWithoutSend() {
        ScheduleEntity row = pendingNotify("s3");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(goalService.get("goal-s3")).thenReturn(Optional.empty());

        assertEquals(0, service.notifyFinished());
        verify(outbox, never()).enqueue(any(), any(), any(), any());
        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("notified_at"));
    }

    /** 入队失败不标记：下一轮会重新收集这条，不能因为一次写库抖动就丢消息 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void notifyFinished_enqueueFails_notMarked() {
        ScheduleEntity row = pendingNotify("s4");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        Goal done = new Goal("goal-s4", "早报");
        done.fail("模型超时");
        when(goalService.get("goal-s4")).thenReturn(Optional.of(done));
        when(outbox.enqueue(any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        assertEquals(0, service.notifyFinished(), "入队失败不计入成功数");
        verify(mapper, never()).update(isNull(), any());
    }

    @Test
    void notifyFinished_nonePending_noop() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service.notifyFinished());
        verify(outbox, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void delete_missingReturnsFalse() {
        when(mapper.deleteById("nope")).thenReturn(0);

        assertFalse(service.delete("nope"));
    }

    @Test
    void delete_blankIdReturnsFalse() {
        assertFalse(service.delete("  "));
        verify(mapper, never()).deleteById(anyString());
    }
}
