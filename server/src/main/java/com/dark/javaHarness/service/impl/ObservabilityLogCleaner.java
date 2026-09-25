package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.mapper.LlmCallLogMapper;
import com.dark.javaHarness.mapper.ToolCallLogMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 观测表保留期清理（llm_call_log / tool_call_log）。
 *
 * <p>背景：观测表只写不删，行数无界增长（优化审查 2026-09-25 高危项）。本任务按保留期
 * 定时分批删除过期行——分批（单批 {@link #BATCH_LIMIT}）避免单语句长事务与 binlog 尖峰；
 * 走 created_at 索引（V24），删除条件不触发全表扫。
 *
 * <p>配置：{@code app.observability.retention-days}（默认 90）；0 = 禁用清理（任务空转），
 * 与预算配置「0 即不设限」的全局口径一致。删除失败仅 warn 不抛——观测清理绝不影响主链路，
 * 下一轮调度自然重试。
 */
@Component
public class ObservabilityLogCleaner {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityLogCleaner.class);

    /** 单批删除上限：控制单语句事务时长，循环直至该批不足量（清完为止） */
    static final int BATCH_LIMIT = 1000;

    private final LlmCallLogMapper llmCallLogMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    /** 保留天数（0 = 禁用清理） */
    private final int retentionDays;

    public ObservabilityLogCleaner(LlmCallLogMapper llmCallLogMapper,
                                   ToolCallLogMapper toolCallLogMapper,
                                   @Value("${app.observability.retention-days:90}") int retentionDays) {
        this.llmCallLogMapper = llmCallLogMapper;
        this.toolCallLogMapper = toolCallLogMapper;
        this.retentionDays = retentionDays;
        log.info("[observability-clean] 观测表清理已装配：保留期 {} 天（0 = 禁用）", retentionDays);
    }

    /** 每日凌晨 3 点执行（低峰期）；上轮未完不会堆叠（单线程调度器串行） */
    @Scheduled(cron = "${app.observability.clean-cron:0 0 3 * * ?}")
    public void cleanOnce() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        long llm = deleteInBatches("llm_call_log", llmCallLogMapper, threshold);
        long tool = deleteInBatches("tool_call_log", toolCallLogMapper, threshold);
        if (llm > 0 || tool > 0) {
            log.info("[observability-clean] 保留期 {} 天：清理 llm_call_log {} 行、tool_call_log {} 行",
                    retentionDays, llm, tool);
        }
    }

    /** 分批删除：每批 BATCH_LIMIT 直到删空（本批不足量即收尾）；失败仅 warn 记账，下轮调度重试 */
    private <T> long deleteInBatches(String table, BaseMapper<T> mapper, LocalDateTime threshold) {
        long total = 0;
        try {
            while (true) {
                QueryWrapper<T> qw = new QueryWrapper<>();
                qw.lt("created_at", threshold).last("LIMIT " + BATCH_LIMIT);
                int deleted = mapper.delete(qw);
                total += deleted;
                if (deleted < BATCH_LIMIT) {
                    break; // 本批不足量 = 已删空
                }
            }
        } catch (Exception e) {
            log.warn("[observability-clean] {} 清理中断（已删 {} 行，下轮重试）: {}",
                    table, total, e.toString());
        }
        return total;
    }
}
