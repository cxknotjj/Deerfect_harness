package com.dark.javaHarness.tool.builtin.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.Memory;
import com.dark.javaHarness.service.MemoryService;
import com.dark.javaHarness.tool.ToolContextKeys;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

/**
 * MemoryTools 单测：写入确认文案、参数非法时的可读错误、忘掉的三种路径
 * （按 id / 关键词唯一命中 / 多命中不删）、列表渲染来源与状态。
 */
@ExtendWith(MockitoExtension.class)
class MemoryToolsTest {

    @Mock
    private MemoryService memoryService;

    private MemoryTools tools;

    @BeforeEach
    void setUp() {
        tools = new MemoryTools(memoryService);
    }

    @Test
    void remember_returnsConfirmationWithIdAndKey() {
        when(memoryService.remember("我的车牌是沪A12345", "车牌", "user", "12", null))
                .thenReturn(memory(7L, "车牌", "我的车牌是沪A12345", null));

        String reply = tools.remember("我的车牌是沪A12345", "车牌", null, ctx("12"));

        assertTrue(reply.contains("已记住"));
        assertTrue(reply.contains("id=7"));
        assertTrue(reply.contains("标签=车牌"));
        assertTrue(reply.contains("长期有效"));
        assertTrue(reply.contains("之后的对话会自动带上"), "应告知跨会话生效");
    }

    @Test
    void remember_sameKey_mentionsReplacement() {
        when(memoryService.countActiveByKey("技术栈")).thenReturn(1);
        when(memoryService.remember(any(), any(), any(), any(), any()))
                .thenReturn(memory(10L, "技术栈", "用户的技术栈是 Rust", null));

        String reply = tools.remember("我的技术栈改成 Rust", "技术栈", null, ctx(null));

        assertTrue(reply.contains("同标签的旧条目已自动替换"), "覆盖发生时必须告知，避免模型以为旧值还在");
    }

    @Test
    void remember_withExpiry_mentionsDeadline() {
        LocalDateTime expires = LocalDateTime.of(2026, 12, 7, 10, 0);
        when(memoryService.remember(any(), any(), any(), any(), any()))
                .thenReturn(memory(9L, null, "临时密码 1234", expires));

        String reply = tools.remember("临时密码 1234", null, 30, ctx(null));

        assertTrue(reply.contains("有效期至 2026-12-07"));
    }

    @Test
    void remember_serviceRejects_returnsReadableMessage() {
        when(memoryService.remember(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("记忆内容不能为空"));

        String reply = tools.remember("   ", null, null, ctx(null));

        assertTrue(reply.startsWith("没能记住："));
        assertTrue(reply.contains("记忆内容不能为空"));
    }

    @Test
    void forget_byId_deletes() {
        when(memoryService.findActive(3L)).thenReturn(Optional.of(memory(3L, null, "车牌号是沪A", null)));
        when(memoryService.forget(3L)).thenReturn(true);

        String reply = tools.forget(3L, null);

        assertTrue(reply.contains("已忘掉"));
        assertTrue(reply.contains("id=3"));
        verify(memoryService).forget(3L);
    }

    @Test
    void forget_byMissingId_doesNotDelete() {
        when(memoryService.findActive(99L)).thenReturn(Optional.empty());

        String reply = tools.forget(99L, null);

        assertTrue(reply.contains("没有找到"));
        verify(memoryService, never()).forget(anyLong());
    }

    @Test
    void forget_byKeywordMultiple_returnsCandidatesWithoutDeleting() {
        when(memoryService.findActiveByKeyword("车牌"))
                .thenReturn(List.of(memory(1L, "车牌", "沪A12345", null), memory(2L, "车牌", "沪B00001", null)));

        String reply = tools.forget(null, "车牌");

        assertTrue(reply.contains("命中 2 条"));
        assertTrue(reply.contains("未删除"), "多命中必须让用户确认，不能猜");
        assertTrue(reply.contains("id=1"));
        assertTrue(reply.contains("id=2"));
        verify(memoryService, never()).forget(anyLong());
    }

    @Test
    void forget_byKeywordSingle_deletes() {
        when(memoryService.findActiveByKeyword("沪A")).thenReturn(List.of(memory(4L, "车牌", "沪A12345", null)));
        when(memoryService.forget(4L)).thenReturn(true);

        String reply = tools.forget(null, "沪A");

        assertTrue(reply.contains("已忘掉"));
        verify(memoryService).forget(4L);
    }

    @Test
    void forget_withoutArguments_returnsUsage() {
        String reply = tools.forget(null, "  ");
        assertTrue(reply.contains("请提供 id"));
        verify(memoryService, never()).findActiveByKeyword(any());
    }

    @Test
    void listMemories_empty_returnsHint() {
        when(memoryService.list(Memory.Status.ACTIVE, 50)).thenReturn(List.of());
        String reply = tools.listMemories();
        assertTrue(reply.contains("没有记住任何事"));
    }

    @Test
    void listMemories_rendersSourceTimeAndStatus() {
        when(memoryService.list(Memory.Status.ACTIVE, 50))
                .thenReturn(List.of(memory(5L, "车牌", "沪A12345", LocalDateTime.of(2026, 12, 7, 0, 0))));

        String reply = tools.listMemories();

        assertTrue(reply.contains("id=5"));
        assertTrue(reply.contains("[车牌]"));
        assertTrue(reply.contains("来源=对话中明示"));
        assertTrue(reply.contains("2026-11-07 记"));
        assertTrue(reply.contains("有效期至 2026-12-07"));
        assertTrue(reply.contains("状态=有效"));
        assertFalse(reply.contains("null"));
    }

    private static ToolContext ctx(String sessionId) {
        return new ToolContext(sessionId == null ? Map.of() : Map.of(ToolContextKeys.SESSION_ID, sessionId));
    }

    private static Memory memory(long id, String key, String content, LocalDateTime expiresAt) {
        return new Memory(id, key, content, "user", "12",
                LocalDateTime.of(2026, 11, 7, 10, 30), expiresAt, Memory.Status.ACTIVE);
    }
}
