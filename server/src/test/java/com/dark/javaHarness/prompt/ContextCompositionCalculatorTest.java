package com.dark.javaHarness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.domain.dto.ContextComposition;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 上下文构成计算器单测：
 * - system 分段映射 + system.other 兜底 + RAG + 工具 Schema
 * - 最后一个 user 计当前消息，其余按角色汇总历史（system 排除）
 * - 占比之和为 100%，总数为 0 时不生成 parts
 */
class ContextCompositionCalculatorTest {

    @Test
    void build_splitsSectionsRagToolsAndConversation() {
        SystemAssembly system = new SystemAssembly(
                "角色提示词\n\n用户偏好画像\n\n知识块",
                List.of(new PromptAssembler.RenderedSection("角色", "角色提示词", 5),
                        new PromptAssembler.RenderedSection("用户偏好画像", "用户偏好画像", 6)),
                3);
        List<Message> messages = List.of(
                new SystemMessage("系统"),
                new AssistantMessage("历史回答"),
                new UserMessage("历史提问"),
                new UserMessage("当前问题"));

        ContextComposition composition = ContextCompositionCalculator.build(
                "s1", "general", "m1", "t1", system, 7, messages);

        assertEquals(34, composition.estimatedTotalTokens(), "各项 token 之和");
        Map<String, ContextComposition.Part> parts = composition.parts().stream()
                .collect(Collectors.toMap(ContextComposition.Part::key, part -> part));
        assertEquals(List.of("system.role", "system.profile", "system.other", "retrieval.rag",
                        "tools.schema", "conversation.history.user",
                        "conversation.history.assistant", "conversation.currentUser"),
                composition.parts().stream().map(ContextComposition.Part::key).toList(),
                "part 输出顺序固定");
        assertEquals(5, parts.get("system.role").tokens());
        assertEquals(6, parts.get("system.profile").tokens());
        assertEquals(1, parts.get("system.other").tokens(), "拼接开销兜底为正数");
        assertEquals(3, parts.get("retrieval.rag").tokens());
        assertEquals(7, parts.get("tools.schema").tokens());
        assertEquals(4, parts.get("conversation.history.user").tokens(), "除当前 user 外的 user 汇总");
        assertEquals(4, parts.get("conversation.history.assistant").tokens());
        assertEquals(4, parts.get("conversation.currentUser").tokens(), "最后一个 user 计当前消息");
        double percentSum = composition.parts().stream()
                .mapToDouble(ContextComposition.Part::percent).sum();
        assertTrue(percentSum >= 99.9 && percentSum <= 100.1,
                "占比之和应约等于 100%，实际: " + percentSum);
        assertEquals(14.7, parts.get("system.role").percent(), 0.001);
    }

    @Test
    void build_emptyInputs_producesNoParts() {
        ContextComposition composition = ContextCompositionCalculator.build(
                "s1", "general", "m1", null, new SystemAssembly("", List.of(), 0), 0, List.of());

        assertEquals(0, composition.estimatedTotalTokens());
        assertTrue(composition.parts().isEmpty(), "总数为 0 时不生成 parts");
        assertEquals(0, composition.parts().stream()
                .mapToDouble(ContextComposition.Part::percent).sum(), 0.0001, "空快照占比为 0");
    }
}
