package com.dark.javaHarness.observability;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.GoalEntity;
import com.dark.javaHarness.domain.entity.SessionMessageArchiveEntity;
import com.dark.javaHarness.mapper.GoalMapper;
import com.dark.javaHarness.mapper.OrchestrationCheckpointMapper;
import com.dark.javaHarness.mapper.SessionMessageArchiveMapper;
import com.dark.javaHarness.state.persistence.OrchestrationCheckpointEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 业务数据保留期清理：会话归档（session_message_archive）与已结束目标（goal）。
 *
 * <p>与 {@link ObservabilityLogCleaner} 的分工：那边清「观测日志」，丢了不影响功能；这边清
 * 「业务数据」，保留期更长、各自可配——归档是 recall_history 的数据源，goal 含 objective /
 * summary 正文，如果和日志共用同一个 90 天，等于把「还想翻旧账」和「日志太占地方」混为一谈。
 *
 * <p>目标清理必须级联删编排检查点：{@code orchestration_checkpoint} 按 goal_id 关联，
 * 只删 goal 会留下既不能被 resume、也再也查不到归属的 state_json 大字段。
 *
 * <p>配置（0 = 禁用该表的清理）：
 * <ul>
 *   <li>{@code app.retention.archive-days}（默认 180）</li>
 *   <li>{@code app.retention.goal-days}（默认 180）</li>
 *   <li>{@code app.retention.clean-cron}（默认 03:30，错开观测清理的 03:00）</li>
 * </ul>
 * 失败仅 warn 不抛：保留期清理是旁路，下一轮调度自然重试。
 */
@Component
public class DataRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionCleaner.class);

    /** 单批上限：控制单语句事务时长与 binlog 尖峰 */
    static final int BATCH_LIMIT = 500;

    /** goal 的终态：只有这两种可以被清理，PENDING / RUNNING 一律不动 */
    private static final List<String> TERMINAL_STATUSES = List.of("SUCCEEDED", "FAILED");

    private final SessionMessageArchiveMapper archiveMapper;
    private final GoalMapper goalMapper;
    private final OrchestrationCheckpointMapper checkpointMapper;
    /** 归档保留天数（0 = 禁用） */
    private final int archiveDays;
    /** 已结束目标保留天数（0 = 禁用） */
    private final int goalDays;

    public DataRetentionCleaner(SessionMessageArchiveMapper archiveMapper,
                                GoalMapper goalMapper,
                                OrchestrationCheckpointMapper checkpointMapper,
                                @Value("${app.retention.archive-days:180}") int archiveDays,
                                @Value("${app.retention.goal-days:180}") int goalDays) {
        this.archiveMapper = archiveMapper;
        this.goalMapper = goalMapper;
        this.checkpointMapper = checkpointMapper;
        this.archiveDays = archiveDays;
        this.goalDays = goalDays;
        log.info("[retention] 业务数据清理已装配：归档 {} 天、已结束目标 {} 天（0 = 禁用，cron 见 app.retention.clean-cron）",
                archiveDays, goalDays);
    }

    /** 每日 03:30（低峰期，错开观测清理）；上轮未完不会堆叠（单线程调度器串行） */
    @Scheduled(cron = "${app.retention.clean-cron:0 30 3 * * ?}")
    public void cleanOnce() {
        long archived = archiveDays > 0 ? purgeArchive(LocalDateTime.now().minusDays(archiveDays)) : 0;
        long goals = goalDays > 0 ? purgeGoals(LocalDateTime.now().minusDays(goalDays)) : 0;
        if (archived > 0 || goals > 0) {
            log.info("[retention] 清理完成：会话归档 {} 行（保留 {} 天）、已结束目标 {} 个（保留 {} 天）",
                    archived, archiveDays, goals, goalDays);
        }
    }

    /** 按归档时间分批删除（走 idx_archive_time）；包可见供测试直接传阈值 */
    long purgeArchive(LocalDateTime threshold) {
        long total = 0;
        try {
            while (true) {
                QueryWrapper<SessionMessageArchiveEntity> qw = new QueryWrapper<>();
                qw.lt("archived_at", threshold).last("LIMIT " + BATCH_LIMIT);
                int deleted = archiveMapper.delete(qw);
                total += deleted;
                if (deleted < BATCH_LIMIT) {
                    break; // 本批不足量 = 已删空
                }
            }
        } catch (Exception e) {
            log.warn("[retention] 会话归档清理中断（已删 {} 行，下轮重试）：{}", total, e.toString());
        }
        return total;
    }

    /**
     * 清理已结束且超过保留期的目标，并级联删除其编排检查点。
     *
     * <p>先按批取出 id（不整表捞 state_json），再删 checkpoint，最后删 goal：
     * 顺序保证「即使删 goal 失败，checkpoint 也已被清理」不会造成不可恢复的孤儿；
     * 反过来的顺序则可能删了 goal 却留着大字段。
     */
    long purgeGoals(LocalDateTime threshold) {
        long total = 0;
        try {
            while (true) {
                QueryWrapper<GoalEntity> qw = new QueryWrapper<>();
                qw.select("id")
                        .in("status", TERMINAL_STATUSES)
                        .lt("finished_at", threshold)
                        .last("LIMIT " + BATCH_LIMIT);
                List<GoalEntity> rows = goalMapper.selectList(qw);
                if (rows == null || rows.isEmpty()) {
                    break;
                }
                List<String> ids = rows.stream().map(GoalEntity::getId).toList();
                checkpointMapper.delete(new QueryWrapper<OrchestrationCheckpointEntity>()
                        .in("goal_id", ids));
                total += goalMapper.deleteBatchIds(ids);
                if (rows.size() < BATCH_LIMIT) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[retention] 已结束目标清理中断（已删 {} 个，下轮重试）：{}", total, e.toString());
        }
        return total;
    }
}
