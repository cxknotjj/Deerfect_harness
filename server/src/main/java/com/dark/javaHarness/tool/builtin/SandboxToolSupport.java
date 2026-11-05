package com.dark.javaHarness.tool.builtin;

import tools.jackson.databind.ObjectMapper;
import io.agentscope.runtime.sandbox.tools.SandboxTool;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 沙箱工具封装的公共能力：把 agentscope {@link SandboxTool} 包成 Spring AI {@link ToolCallback}。
 * 仅 SandboxShellTools / SandboxFileTools / SandboxBrowserTools 使用（包内可见）。
 */
final class SandboxToolSupport {

    private static final Logger log = LoggerFactory.getLogger(SandboxToolSupport.class);

    private SandboxToolSupport() {
    }

    /**
     * 通用封装：名称/描述/入参 schema 取 agentscope 工具定义（与旧 SAA 同源），
     * 入参按 {@code Map} 接收后由各工具分派器转成对应方法参数。
     */
    static ToolCallback callback(SandboxTool tool,
                                 BiFunction<Map<String, Object>, ToolContext, String> executor,
                                 String message) {
        ObjectMapper mapper = new ObjectMapper();
        String inputSchema = "";
        try {
            inputSchema = mapper.writeValueAsString(tool.getSchema());
        } catch (Exception e) {
            log.error("生成工具入参 schema 失败: {}", e.getMessage());
        }
        // 用 LinkedHashMap（而非 Map.of）：工具失败信息/部分实现可能返回 null，Map.of 拒绝 null
        BiFunction<Map<String, Object>, ToolContext, Map<String, Object>> function = (args, ctx) -> {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("result", executor.apply(args, ctx));
            body.put("message", message);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("Response", body);
            return response;
        };
        return FunctionToolCallback
                .builder(tool.getName(), function)
                .description(tool.getDescription())
                .inputSchema(inputSchema)
                .inputType(Map.class)
                .toolMetadata(ToolMetadata.builder().returnDirect(false).build())
                .build();
    }

    static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static String[] strArray(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toArray(String[]::new);
        }
        return null;
    }

    static Boolean bool(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Boolean b ? b : null;
    }
}
