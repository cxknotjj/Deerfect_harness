package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.Schedule;
import com.dark.javaHarness.service.ScheduleService;
import com.dark.javaHarness.view.ScheduleCreateRequest;
import com.dark.javaHarness.view.SchedulesView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务管理接口（表现层，纯转发）：定义「什么时候让它自己开始干活」。
 *
 * <p>任务的执行结果不在这里查——每次触发都产生一个 Goal，状态走
 * {@code GET /api/harness/goals/{id}}（返回体里的 lastGoalId 即指针）。
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 创建定时任务；cron 为 Spring 6 段表达式（秒 分 时 日 月 周） */
    @PostMapping
    public Schedule create(@RequestBody ScheduleCreateRequest request) {
        return scheduleService.create(request.objective(), request.agentName(),
                request.cron(), request.notifyChannel());
    }

    /** 列出全部定时任务 */
    @GetMapping
    public SchedulesView list() {
        return new SchedulesView(scheduleService.all());
    }

    /** 删除定时任务（已触发的 goal 不受影响） */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        if (!scheduleService.delete(id)) {
            throw new IllegalArgumentException("定时任务不存在: " + id);
        }
    }
}
