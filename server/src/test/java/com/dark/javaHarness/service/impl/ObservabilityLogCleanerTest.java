package com.dark.javaHarness.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.mapper.LlmCallLogMapper;
import com.dark.javaHarness.mapper.ToolCallLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 观测表保留期清理单测：禁用开关、分批循环、失败不抛三边界。
 */
@ExtendWith(MockitoExtension.class)
class ObservabilityLogCleanerTest {

    @Mock
    private LlmCallLogMapper llmCallLogMapper;
    @Mock
    private ToolCallLogMapper toolCallLogMapper;

    private ObservabilityLogCleaner cleaner(int retentionDays) {
        return new ObservabilityLogCleaner(llmCallLogMapper, toolCallLogMapper, retentionDays);
    }

    /** retention-days=0：禁用清理，两个 mapper 均不被触碰 */
    @Test
    void cleanOnce_zeroRetention_disables() {
        cleaner(0).cleanOnce();
        verify(llmCallLogMapper, never()).delete(any());
        verify(toolCallLogMapper, never()).delete(any());
    }

    /** 正常清理：两张表各删一批（返回量 < 单批上限即收尾） */
    @Test
    void cleanOnce_deletesBothTables() {
        when(llmCallLogMapper.delete(any())).thenReturn(3);
        when(toolCallLogMapper.delete(any())).thenReturn(0);

        cleaner(90).cleanOnce();

        verify(llmCallLogMapper, times(1)).delete(any());
        verify(toolCallLogMapper, times(1)).delete(any());
    }

    /** 分批循环：单批满 1000 行则继续删下一批，直到不足量收尾；总数累计正确 */
    @Test
    void cleanOnce_batchLoop_untilExhausted() {
        when(llmCallLogMapper.delete(any())).thenReturn(ObservabilityLogCleaner.BATCH_LIMIT, 3);
        when(toolCallLogMapper.delete(any())).thenReturn(0);

        cleaner(90).cleanOnce();

        verify(llmCallLogMapper, times(2)).delete(any());
    }

    /** 删除条件走 created_at < 阈值（V24 索引列），且带 LIMIT 批量上限 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cleanOnce_wrapperTargetsCreatedAtWithLimit() {
        when(llmCallLogMapper.delete(any())).thenReturn(0);
        when(toolCallLogMapper.delete(any())).thenReturn(0);

        cleaner(90).cleanOnce();

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(llmCallLogMapper).delete(captor.capture());
        String sql = ((QueryWrapper<?>) captor.getValue()).getTargetSql();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("created_at"),
                "删除条件应为 created_at 列，实际: " + sql);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("LIMIT"),
                "删除应带分批 LIMIT，实际: " + sql);
    }

    /** 删除抛异常：不向上传播（观测清理绝不影响主链路），且另一张表仍继续处理 */
    @Test
    void cleanOnce_failureSwallowed_continuesOtherTable() {
        when(llmCallLogMapper.delete(any())).thenThrow(new RuntimeException("db down"));
        when(toolCallLogMapper.delete(any())).thenReturn(0);

        cleaner(90).cleanOnce();

        verify(toolCallLogMapper, times(1)).delete(any());
    }
}
