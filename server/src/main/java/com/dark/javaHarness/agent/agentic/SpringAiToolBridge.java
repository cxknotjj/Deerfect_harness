package com.dark.javaHarness.agent.agentic;

import com.dark.javaHarness.tool.trace.ToolCallTracer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;

/**
 * Spring AI {@link ToolCallback} → AgentScope {@code ToolBase} 桥接。
 *
 * <p>所有工具（自研 / 沙箱 / MCP）仍由 Spring AI 提供并经既有装饰链（观测→预算→懒加载→元工具）
 * 装饰；本类只做参数与结果的双向转换，不复制工具实现，也不注册 AgentScope 自带 MCP client。
 * 轨迹标识经 AgentScope {@code RuntimeContext} 读出后透传 Spring AI {@code ToolContext}，
 * 保证 tool_call_log 的 turn/trace/parent_span 口径与 ChatClient 路径一致。
 */
public final class SpringAiToolBridge extends ToolBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback callback;

    public SpringAiToolBridge(ToolCallback callback) {
        super(ToolBase.builder()
                .name(callback.getToolDefinition().name())
                .description(callback.getToolDefinition().description())
                .inputSchema(parseSchema(callback.getToolDefinition().inputSchema()))
                .readOnly(false)
                .concurrencySafe(true));
        this.callback = callback;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        // 工具可见性已由服务端 agent.tools 白名单约束，桥接层不引入二次确认
        return Mono.just(PermissionDecision.allow("visibility constrained by agent tool whitelist"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            String inputJson = MAPPER.writeValueAsString(
                    param.getInput() == null ? Map.of() : param.getInput());
            try {
                String result = callback.call(inputJson, toolContext(param.getRuntimeContext()));
                return ToolResultBlock.builder()
                        .id(param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId())
                        .name(getName())
                        .output(List.<ContentBlock>of(TextBlock.builder().text(result).build()))
                        .build();
            } catch (Exception e) {
                // 工具失败以结构化结果回给模型（与 Spring AI 工具异常语义一致），不炸整个 ReAct 循环
                return ToolResultBlock.error("工具执行失败：" + describe(e));
            }
        });
    }

    /** 把 AgentScope per-call 上下文中的轨迹标识转成 Spring AI ToolContext（缺失键不放入）。 */
    private static ToolContext toolContext(RuntimeContext runtime) {
        Map<String, Object> map = new HashMap<>(4);
        if (runtime != null) {
            putIfPresent(map, ToolCallTracer.CTX_TURN_ID, runtime.get(ToolCallTracer.CTX_TURN_ID));
            putIfPresent(map, ToolCallTracer.CTX_TRACE_ID, runtime.get(ToolCallTracer.CTX_TRACE_ID));
            putIfPresent(map, ToolCallTracer.CTX_PARENT_SPAN, runtime.get(ToolCallTracer.CTX_PARENT_SPAN));
        }
        return new ToolContext(map);
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static Map<String, Object> parseSchema(String schema) {
        try {
            return MAPPER.readValue(schema, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
