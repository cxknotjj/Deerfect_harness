package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.Memory;
import com.dark.javaHarness.service.MemoryService;
import com.dark.javaHarness.view.MemoriesView;
import com.dark.javaHarness.view.MemoryCreateRequest;
import com.dark.javaHarness.view.MemoryView;
import java.util.Locale;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 显式记忆管理接口（表现层，纯转发）：给 CLI 与管理页提供「记住 / 忘掉 / 查看」的入口。
 *
 * <p>对话内的写入走 {@code remember} 工具（source=user），这里写入的记为 source=manual，
 * 便于在 list 里区分「用户当面说的」与「命令行补录的」。
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 列出记忆：status=active（默认）/ expired / deleted；limit 默认 50 */
    @GetMapping
    public MemoriesView list(@RequestParam(defaultValue = "active") String status,
                             @RequestParam(defaultValue = "50") int limit) {
        Memory.Status target = parseStatus(status);
        return new MemoriesView(memoryService.list(target, limit).stream()
                .map(MemoryController::toView)
                .toList());
    }

    /** 新增一条记忆（content 必填；同 key 覆盖旧记忆） */
    @PostMapping
    public MemoryView create(@RequestBody MemoryCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Memory memory = memoryService.remember(request.content(), request.key(), "manual", null,
                request.expiresAt());
        return toView(memory);
    }

    /** 软删一条记忆（保留审计，不再注入） */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        if (!memoryService.forget(id)) {
            throw new IllegalArgumentException("记忆不存在或已删除: " + id);
        }
    }

    private static Memory.Status parseStatus(String status) {
        return switch (status == null ? "active" : status.strip().toLowerCase(Locale.ROOT)) {
            case "active", "" -> Memory.Status.ACTIVE;
            case "expired" -> Memory.Status.EXPIRED;
            case "deleted" -> Memory.Status.DELETED;
            default -> throw new IllegalArgumentException("status 只支持 active / expired / deleted");
        };
    }

    private static MemoryView toView(Memory memory) {
        return new MemoryView(memory.id(), memory.key(), memory.content(), memory.source(),
                memory.sessionId(), memory.createdAt(), memory.expiresAt(), memory.status().name());
    }
}
