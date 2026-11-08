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

    private DataRetentionCleaner cleaner(int archiveDays, int goalDays) {
        return new DataRetentionCleaner(archiveMapper, goalMapper, checkpointMapper, archiveDays, goalDays);
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
}
