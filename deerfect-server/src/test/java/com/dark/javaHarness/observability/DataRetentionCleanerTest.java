package com.dark.javaHarness.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dark.javaHarness.domain.entity.GoalEntity;
import com.dark.javaHarness.domain.entity.SessionMessageArchiveEntity;
import com.dark.javaHarness.mapper.GoalMapper;
import com.dark.javaHarness.mapper.OrchestrationCheckpointMapper;
import com.dark.javaHarness.mapper.SessionMessageArchiveMapper;
import com.dark.javaHarness.state.persistence.OrchestrationCheckpointEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 业务数据保留期清理单测：禁用开关、分批循环、目标级联删检查点、失败不抛。
 *
 * <p>重点在「删 goal 时先把 checkpoint 删掉」——只删 goal 会留下既不能 resume、
 * 也查不到归属的 state_json 大字段，这是本清理器存在的核心理由。
 */
@ExtendWith(MockitoExtension.class)
class DataRetentionCleanerTest {

    @Mock
    private SessionMessageArchiveMapper archiveMapper;
    @Mock
    private GoalMapper goalMapper;
    @Mock
    private OrchestrationCheckpointMapper checkpointMapper;
    @Mock
    private com.dark.javaHarness.mapper.DeliveryOutboxMapper outboxMapper;

    private DataRetentionCleaner cleaner(int archiveDays, int goalDays) {
        // outbox 保留期在这组用例里关闭（0 = 禁用），避免干扰「归档 / goal」的断言
        return new DataRetentionCleaner(archiveMapper, goalMapper, checkpointMapper, outboxMapper,
                archiveDays, goalDays, 0, 0);
    }

    private DataRetentionCleaner cleanerWithOutbox(int sentDays, int deadDays) {
        return new DataRetentionCleaner(archiveMapper, goalMapper, checkpointMapper, outboxMapper,
                0, 0, sentDays, deadDays);
    }

    private static GoalEntity goal(String id) {
        GoalEntity entity = new GoalEntity();
        entity.setId(id);
        return entity;
    }

    /** 两张表都禁用：三个 mapper 均不被触碰 */
    @Test
    void cleanOnce_bothDisabled_touchesNothing() {
        cleaner(0, 0).cleanOnce();

        verify(archiveMapper, never()).delete(any());
        verify(goalMapper, never()).selectList(any());
        verify(checkpointMapper, never()).delete(any());
    }

    /** 归档分批：满批后继续，不足量收尾 */
    @Test
    void purgeArchive_batchesUntilBelowLimit() {
        when(archiveMapper.delete(any())).thenReturn(DataRetentionCleaner.BATCH_LIMIT, 10);

        long deleted = cleaner(180, 0).purgeArchive(LocalDateTime.now());

        assertEquals(DataRetentionCleaner.BATCH_LIMIT + 10, deleted);
        verify(archiveMapper, times(2)).delete(any());
    }

    /** 删目标前先删其检查点，且只对终态生效 */
    @Test
    @SuppressWarnings("unchecked")
    void purgeGoals_cascadesCheckpointsAndFiltersTerminalStatus() {
        when(goalMapper.selectList(any())).thenReturn(List.of(goal("g1"), goal("g2")));
        when(goalMapper.deleteBatchIds(anyList())).thenReturn(2);

        long deleted = cleaner(0, 180).purgeGoals(LocalDateTime.now().minusDays(180));

        assertEquals(2, deleted);

        ArgumentCaptor<Wrapper<GoalEntity>> goalCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(goalMapper).selectList(goalCaptor.capture());
        String sql = goalCaptor.getValue().getSqlSegment();
        assertTrue(sql.contains("status") && sql.contains("IN"), "只能删终态目标，实际条件: " + sql);
        assertTrue(sql.contains("finished_at"), "必须按完成时间判断保留期，实际条件: " + sql);

        ArgumentCaptor<Wrapper<OrchestrationCheckpointEntity>> cpCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(checkpointMapper).delete(cpCaptor.capture());
        String cpSql = cpCaptor.getValue().getSqlSegment();
        assertTrue(cpSql.contains("goal_id"), "检查点按 goal_id 级联，实际条件: " + cpSql);

        verify(goalMapper).deleteBatchIds(List.of("g1", "g2"));
    }

    /** 没有可删目标时不调用删除（避免空 IN 语句） */
    @Test
    void purgeGoals_emptyBatch_stopsWithoutDelete() {
        when(goalMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, cleaner(0, 180).purgeGoals(LocalDateTime.now()));

        verify(checkpointMapper, never()).delete(any());
        verify(goalMapper, never()).deleteBatchIds(anyList());
    }

    /** 删除失败只告警不抛：清理是旁路，不能打断调度线程 */
    @Test
    void purgeArchive_failureDoesNotThrow() {
        when(archiveMapper.delete(any())).thenThrow(new IllegalStateException("db down"));

        assertEquals(0, cleaner(180, 0).purgeArchive(LocalDateTime.now()));
        verify(archiveMapper, atLeastOnce()).delete(any());
    }

    // ---------- 投递记录（outbox） ----------

    /** 只清终态，且按 created_at 分批：PENDING 是还没送出去的消息，任何情况下都不该删 */
    @Test
    void purgeOutbox_onlyTerminalStatuses() {
        when(outboxMapper.delete(any())).thenReturn(3);

        assertEquals(3, cleanerWithOutbox(7, 30).purgeOutbox("SENT", LocalDateTime.now().minusDays(7)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<com.dark.javaHarness.domain.entity.DeliveryOutboxEntity>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(outboxMapper).delete(captor.capture());
        // delete() 收的是 Wrapper，实际传入的是 QueryWrapper（条件构造器的参数在 AbstractWrapper 上）
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.dark.javaHarness.domain.entity.DeliveryOutboxEntity> qw =
                (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.dark.javaHarness.domain.entity.DeliveryOutboxEntity>) captor.getValue();
        qw.getSqlSegment();
        assertTrue(qw.getParamNameValuePairs().containsValue("SENT"),
                "状态条件要落到 SENT，不能扫全表：" + qw.getSqlSegment());
        assertTrue(qw.getSqlSegment().contains("created_at"), "按入队时间分批：" + qw.getSqlSegment());
    }

    @Test
    void purgeOutbox_disabled_neverTouchesMapper() {
        // 全部关闭时 cleanOnce 什么都不该碰（严格 mock 会抓「多打了多余的 SQL」）
        cleanerWithOutbox(0, 0).cleanOnce();

        verify(outboxMapper, never()).delete(any());
    }

    @Test
    void purgeOutbox_failure_swallowed() {
        when(outboxMapper.delete(any())).thenThrow(new IllegalStateException("db down"));

        assertEquals(0, cleanerWithOutbox(7, 30).purgeOutbox("DEAD", LocalDateTime.now().minusDays(30)),
                "清理失败只告警，不能抛给调度器");
    }
}
