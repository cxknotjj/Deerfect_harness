package com.dark.javaHarness.advisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.domain.ChatMessageMetadata;
import com.dark.javaHarness.service.SessionSummaryPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * ContextAssemblingAdvisor 上下文组装单测：
 * - 过滤空消息与系统占位噪声
 * - 归一化 role 顺序（system 置前，user/assistant 交替）
 * - 按 token 预算从旧丢弃（保留 system + 最近消息）
 */
class ContextAssemblingAdvisorTest {

    private static final long BIG_TEXT = 4000L; // 用于触发 token 预算裁剪

    private ContextAssemblingAdvisor advisor() {
        return new ContextAssemblingAdvisor(4000, null, null);
    }

    private String longText(long n) {
        return "a".repeat((int) n);
    }

    @Test
    void assemble_filtersEmptyMessages() {
        ContextAssemblingAdvisor a = advisor();
        List<Message> result = a.assemble(List.of(
                new UserMessage("   "),
                new UserMessage("你好")));

        assertEquals(1, result.size(), "空消息应被过滤");
        assertEquals("你好", result.get(0).getText());
    }

    @Test
    void assemble_systemPlacedFirst() {
        ContextAssemblingAdvisor a = advisor();
        List<Message> result = a.assemble(List.of(
                new UserMessage("问题"),
                new SystemMessage("你是助手")));

        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof SystemMessage, "system 应置于最前");
        assertTrue(result.get(1) instanceof UserMessage);
    }

    @Test
    void assemble_mergesConsecutiveSameRole() {
        ContextAssemblingAdvisor a = advisor();
        List<Message> result = a.assemble(List.of(
                new UserMessage("问1"),
                new UserMessage("问2"),
                new AssistantMessage("答2")));

        // 两个连续的 user → 合并为最新一个（问2）
        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof UserMessage);
        assertEquals("问2", result.get(0).getText(), "连续 user 应保留最后一个");
        assertTrue(result.get(1) instanceof AssistantMessage);
    }

    @Test
    void assemble_trimsOldMessagesByTokenBudget_keepsRecent() {
        // 构造超预算历史：system + 很长的旧 user + 短的最近 assistant
        ContextAssemblingAdvisor small = new ContextAssemblingAdvisor(100, null, null);
        List<Message> result = small.assemble(List.of(
                new SystemMessage("system-prompt"),
                new UserMessage(longText(BIG_TEXT)),  // 旧且超长
                new AssistantMessage("short-answer")));

        // token 超预算 → 从最旧丢弃那个超长 user（非 system），保留其余
        assertFalse(result.stream().anyMatch(m -> m.getText().equals(longText(BIG_TEXT))),
                "超预算的最旧长消息应被丢弃");
        assertTrue(result.stream().anyMatch(m -> m instanceof SystemMessage), "system 应保留");
    }

    @Test
    void assemble_trimmedHistoryWithIds_addsArchiveReference() {
        ContextAssemblingAdvisor small = new ContextAssemblingAdvisor(100, null, null);
        UserMessage old = UserMessage.builder()
                .text(longText(BIG_TEXT))
                .metadata(Map.of(ChatMessageMetadata.MESSAGE_ID, "m-old"))
                .build();

        List<Message> result = small.assemble(List.of(
                new SystemMessage("system-prompt"),
                old,
                new AssistantMessage("short-answer")));

        String systemText = result.stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .collect(Collectors.joining());
        assertTrue(systemText.contains("历史引用"), "被裁掉的历史应追加归档引用");
        assertTrue(systemText.contains("m-old"), "引用应带消息 ID 示例");
        assertEquals(2, result.size(), "引用应合并进 system，不新增独立消息");
    }

    @Test
    void assemble_underBudget_preservesAll() {
        ContextAssemblingAdvisor a = advisor(); // 默认 4000
        List<Message> result = a.assemble(List.of(
                new SystemMessage("s"),
                new UserMessage("hi"),
                new AssistantMessage("hello")));

        assertEquals(3, result.size());
    }

    /* ---------------- P2-3 长期记忆滚动摘要 ---------------- */

    /** 记录型摘要端口：读返回固定摘要，写记录被裁消息；fail=true 模拟读写均失败 */
    private static final class RecordingSummaryPort implements SessionSummaryPort {
        String summary;
        boolean fail;
        final List<Message> dropped = new ArrayList<>();

        @Override
        public String summaryOf(String sessionId) {
            if (fail) throw new IllegalStateException("summary store down");
            return summary;
        }

        @Override
        public void requestSummary(String sessionId, List<Message> droppedMessages) {
            if (fail) throw new IllegalStateException("summary llm down");
            dropped.addAll(droppedMessages);
        }

        @Override
        public void deleteBySessionId(String sessionId) {
            dropped.clear(); // 本测试只关心投递，删除按空实现
        }
    }

    @Test
    void assemble_injectsExistingSummaryIntoSystemSegment() {
        RecordingSummaryPort port = new RecordingSummaryPort();
        port.summary = "[约束] 用户要求回答使用中文";
        ContextAssemblingAdvisor a = new ContextAssemblingAdvisor(4000, "s1", port);

        List<Message> result = a.assemble(List.of(
                new SystemMessage("system-prompt"),
                new UserMessage("继续")));

        assertEquals(2, result.size(), "摘要应合并进 system，不新增独立消息");
        String systemText = result.get(0).getText();
        assertTrue(systemText.contains("system-prompt"));
        assertTrue(systemText.contains("【历史摘要】"));
        assertTrue(systemText.contains("[约束] 用户要求回答使用中文"));
    }

    @Test
    void assemble_trimsAndReportsDroppedMessagesToSummaryPort() {
        RecordingSummaryPort port = new RecordingSummaryPort();
        ContextAssemblingAdvisor small = new ContextAssemblingAdvisor(100, "s1", port);
        UserMessage old = UserMessage.builder()
                .text(longText(BIG_TEXT))
                .metadata(Map.of(ChatMessageMetadata.MESSAGE_ID, "m-old"))
                .build();

        List<Message> result = small.assemble(List.of(
                new SystemMessage("system-prompt"),
                old,
                new AssistantMessage("short-answer")));

        assertEquals(1, port.dropped.size(), "被裁掉的消息应投递给摘要端口");
        assertEquals(longText(BIG_TEXT), port.dropped.get(0).getText());
        String systemText = result.stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .collect(Collectors.joining());
        assertTrue(systemText.contains("历史引用"), "归档引用保持现状");
    }

    @Test
    void assemble_summaryPortFailure_degradesWithoutBreakingAssembly() {
        RecordingSummaryPort port = new RecordingSummaryPort();
        port.fail = true;
        ContextAssemblingAdvisor small = new ContextAssemblingAdvisor(100, "s1", port);

        List<Message> result = small.assemble(List.of(
                new SystemMessage("system-prompt"),
                new UserMessage(longText(BIG_TEXT)),
                new AssistantMessage("short-answer")));

        // 摘要读写失败：组装照常完成（丢最旧 + 归档引用），不抛出
        assertFalse(result.stream().anyMatch(m -> m.getText().equals(longText(BIG_TEXT))));
        assertTrue(result.stream().anyMatch(m -> m instanceof SystemMessage));
    }

    @Test
    void assemble_underBudget_doesNotReportDroppedMessages() {
        RecordingSummaryPort port = new RecordingSummaryPort();
        ContextAssemblingAdvisor a = new ContextAssemblingAdvisor(4000, "s1", port);

        a.assemble(List.of(new SystemMessage("s"), new UserMessage("hi"), new AssistantMessage("hello")));

        assertTrue(port.dropped.isEmpty(), "未发生裁剪不应触发摘要生成");
    }

    @Test
    void assemble_thirtyTurnsSmallBudget_reportsEarlyFactsToSummary() {
        RecordingSummaryPort port = new RecordingSummaryPort();
        ContextAssemblingAdvisor small = new ContextAssemblingAdvisor(120, "s1", port);
        List<Message> history = new java.util.ArrayList<>();
        history.add(new SystemMessage("system"));
        for (int i = 1; i <= 30; i++) {
            history.add(UserMessage.builder()
                    .text("第" + i + "轮问题：关键事实" + i + " " + longText(30))
                    .metadata(Map.of(ChatMessageMetadata.MESSAGE_ID, "u" + i))
                    .build());
            history.add(new AssistantMessage("第" + i + "轮回答 " + longText(30)));
        }

        List<Message> result = small.assemble(history);

        // 30 轮超预算：第 1–3 轮的关键事实应进入被裁集合（供摘要压缩），最近轮次仍留在窗口
        assertTrue(port.dropped.stream().anyMatch(m -> m.getText().contains("第1轮问题：关键事实1")),
                "第 1 轮关键事实应被交给摘要");
        assertTrue(port.dropped.stream().anyMatch(m -> m.getText().contains("第3轮问题：关键事实3")),
                "第 3 轮关键事实应被交给摘要");
        assertFalse(result.stream().anyMatch(m -> m.getText().contains("第1轮问题")),
                "被摘要覆盖的最旧轮次应移出窗口");
        assertTrue(result.stream().anyMatch(m -> m.getText().contains("第30轮")), "最近轮次应保留");
    }

    @Test
    void assemble_preservesModelOptions() {
        // 防止回归：重建 Prompt 时必须保留原 options（尤其 model 参数，否则会 400）
        ContextAssemblingAdvisor a = advisor();
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("qwen3.7-plus").build();
        Prompt original = new Prompt(List.of(new UserMessage("hi")), options);

        Prompt assembled = a.assemble(original);

        assertEquals("qwen3.7-plus", assembled.getOptions().getModel(), "组装后应保留 model 参数");
    }
}