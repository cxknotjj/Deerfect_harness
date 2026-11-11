package com.dark.javaHarness.agent.agentic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * AgentScope ReActAgent 隔离 PoC（路线 1：AgentScope 模型扩展）。
 *
 * <p>验证四件事：
 * <ul>
 *   <li>agentscope-core 与项目现有 MCP SDK 2.1.0 / Spring Boot 4 同 classpath 可启动（Toolkit 类加载不炸）；</li>
 *   <li>{@code streamEvents} 能产出文本增量与工具调用事件；</li>
 *   <li>{@code Toolkit.registerTool} 的反射工具可被真实模型调用；</li>
 *   <li>Spring AI {@link ToolCallback} 能桥接成 AgentScope {@code ToolBase}（MCP 工具走这条通道）。</li>
 * </ul>
 *
 * <p>真实模型用例需要 DeepSeek Key（可用 POC_AGENT_BASE_URL / POC_AGENT_MODEL 覆盖），
 * 无 Key 时自动跳过；桥接用例不依赖网络，始终执行。
 */
class AgentScopeReActPoC {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void reactLoopCallsSpringRegisteredStyleToolAndStreamsEvents() {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                .baseUrl(envOrDefault("POC_AGENT_BASE_URL", DEFAULT_BASE_URL))
                .modelName(envOrDefault("POC_AGENT_MODEL", DEFAULT_MODEL))
                .stream(true)
                .formatter(new OpenAIChatFormatter())
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PocTools());

        ReActAgent agent = ReActAgent.builder()
                .name("poc-agent")
                .sysPrompt("你是测试助手。用户要求调用工具时必须先调用工具，再依据工具结果回答。")
                .model(model)
                .toolkit(toolkit)
                .maxIters(4)
                .build();

        RuntimeContext context = RuntimeContext.builder()
                .userId("poc-user")
                .sessionId("poc-" + UUID.randomUUID())
                .build();

        List<AgentEvent> events = agent.streamEvents(
                        new UserMessage("请调用 poc_now 工具一次，然后把工具返回的时间原样告诉我。"),
                        context)
                .collectList()
                .block(Duration.ofSeconds(120));

        assertNotNull(events, "事件流不应为空");
        String types = events.stream().map(e -> e.getType().name()).distinct().collect(Collectors.joining(","));
        System.out.println("[poc] event types = " + types);

        List<ToolCallStartEvent> toolCalls = events.stream()
                .filter(e -> e.getType() == AgentEventType.TOOL_CALL_START)
                .map(ToolCallStartEvent.class::cast)
                .toList();
        assertFalse(toolCalls.isEmpty(), "模型应触发至少一次工具调用，实际事件：" + types);
        assertTrue(toolCalls.stream().anyMatch(c -> "poc_now".equals(c.getToolCallName())),
                "应调用 poc_now，实际：" + toolCalls.stream().map(ToolCallStartEvent::getToolCallName).toList());

        String text = events.stream()
                .filter(e -> e.getType() == AgentEventType.TEXT_BLOCK_DELTA)
                .map(TextBlockDeltaEvent.class::cast)
                .map(TextBlockDeltaEvent::getDelta)
                .collect(Collectors.joining());
        System.out.println("[poc] final text = " + text);
        assertFalse(text.isBlank(), "应产出最终文本");
    }

    @Test
    void springAiToolCallbackBridgesIntoAgentScopeToolkit() {
        ToolCallback callback = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("poc_spring_time")
                        .description("PoC: Spring AI ToolCallback bridged into AgentScope")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "spring-ai-ok";
            }
        };

        SpringAiToolBridge bridge = new SpringAiToolBridge(callback);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(bridge);

        assertEquals("poc_spring_time", bridge.getName());
        assertEquals("object", bridge.getParameters().get("type"));

        ToolResultBlock result = bridge.callAsync(ToolCallParam.builder().input(Map.of()).build())
                .block(Duration.ofSeconds(5));
        assertNotNull(result, "桥接调用应返回结果");
        String text = result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining());
        assertEquals("spring-ai-ok", text);
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 反射注册的 PoC 工具：无入参、只读，便于验证 Toolkit 与工具结果回填。 */
    static class PocTools {

        @Tool(name = "poc_now", description = "返回服务器当前时间的 ISO-8601 字符串", readOnly = true)
        public String pocNow() {
            return Instant.now().toString();
        }
    }
}
