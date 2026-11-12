package com.dark.javaHarness.view;

import java.util.List;

/** 记忆列表视图（GET /api/memories） */
public record MemoriesView(List<MemoryView> items) {
}
