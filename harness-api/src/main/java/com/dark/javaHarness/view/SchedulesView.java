package com.dark.javaHarness.view;

import com.dark.javaHarness.domain.Schedule;
import java.util.List;

/**
 * 定时任务列表视图（GET /api/schedules）。
 */
public record SchedulesView(List<Schedule> items) {
}
