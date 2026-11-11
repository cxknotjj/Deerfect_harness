package com.dark.javaHarness.tool.external;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 声明式 HTTP 工具的运行体：把「资源 + 参数」翻译成一次 GET，并把响应整形成模型能读的文本。
 *
 * <p>三条边界刻意写死：
 * <ul>
 *   <li>路径只能来自清单，模型只能选资源与参数值——不给拼接路径的能力，避免 SSRF</li>
 *   <li>参数必须在该资源的白名单里，未知参数直接报错并列出可用项（让模型自我纠正，而不是静默丢弃）</li>
 *   <li>失败一律返回可读文本而不是抛异常：外部系统不可用不该中断整个回合</li>
 * </ul>
 */
public final class HttpToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(HttpToolCallback.class);

    private final HttpToolManifest manifest;
    private final HttpTransport transport;
    private final ObjectMapper mapper;
    private final String token;
    private final ToolDefinition definition;

    HttpToolCallback(HttpToolManifest manifest, HttpTransport transport, String token, ObjectMapper mapper) {
        this.manifest = manifest;
        this.transport = transport;
        this.token = token;
        this.mapper = mapper;
        this.definition = DefaultToolDefinition.builder()
                .name(manifest.tool().name())
                .description(manifest.tool().description())
                .inputSchema(schema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext context) {
        JsonNode input;
        try {
            input = mapper.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
        } catch (Exception e) {
            return "错误：参数不是合法 JSON";
        }

        String resourceName = input.path(manifest.tool().resourceParam()).asText("");
        if (resourceName.isBlank()) {
            return "错误：必须指定 " + manifest.tool().resourceParam()
                    + "，可选值：" + String.join(" / ", manifest.resources().keySet());
        }
        HttpToolManifest.Resource resource = manifest.resources().get(resourceName);
        if (resource == null) {
            return "错误：未知 " + manifest.tool().resourceParam() + "=" + resourceName
                    + "，可选值：" + String.join(" / ", manifest.resources().keySet());
        }

        Map<String, String> query = new LinkedHashMap<>(resource.fixed());
        List<String> unknown = new ArrayList<>();
        JsonNode params = input.path(manifest.tool().paramsParam());
        // 有的模型会把嵌套对象序列化成字符串（"params":"{\"id\":\"p1\"}"），两种形态都接
        if (params.isTextual()) {
            try {
                params = mapper.readTree(params.asText());
            } catch (Exception e) {
                return "错误：" + manifest.tool().paramsParam() + " 不是合法 JSON 对象";
            }
        }
        if (params.isObject()) {
            for (Map.Entry<String, JsonNode> entry : params.properties()) {
                String key = entry.getKey();
                if (!resource.params().contains(key)) {
                    unknown.add(key);
                    continue;
                }
                String value = text(entry.getValue());
                if (!value.isBlank()) {
                    query.put(key, value);
                }
            }
        } else if (!params.isMissingNode() && !params.isNull()) {
            return "错误：" + manifest.tool().paramsParam() + " 必须是对象（键值对）";
        }
        if (!unknown.isEmpty()) {
            return "错误：" + resourceName + " 不支持参数 " + String.join(", ", unknown)
                    + "；可用参数：" + (resource.params().isEmpty() ? "（无）" : String.join(", ", resource.params()));
        }
        for (String required : resource.requires()) {
            if (!query.containsKey(required)) {
                return "错误：" + resourceName + " 缺少必填参数 " + required;
            }
        }
        if (!manifest.configured()) {
            return "错误：外部接口未配置（缺少 " + manifest.requiredEnvHint() + "），"
                    + "配置后重试；这只是环境缺失，不是查询写错了。";
        }

        HttpTransport.Response response;
        try {
            response = transport.get(url(resource, query), headers(), Duration.ofMillis(manifest.timeoutMs()));
        } catch (java.net.http.HttpTimeoutException e) {
            return "错误：请求超时（" + manifest.timeoutMs() + "ms），外部系统可能繁忙";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "错误：请求被中断";
        } catch (Exception e) {
            log.debug("[http工具] {} 调用失败：{}", manifest.tool().name(), e.toString());
            return "错误：请求失败 - " + e.getMessage();
        }

        if (response.status() / 100 != 2) {
            return "错误：HTTP " + response.status() + " - " + truncate(response.body(), 500);
        }
        return format(response.body());
    }

    private String schema() {
        String resources = "可选值：" + String.join(" / ", manifest.resources().keySet());
        return "{\"type\":\"object\",\"properties\":{"
                + "\"" + manifest.tool().resourceParam() + "\":{\"type\":\"string\",\"enum\":"
                + toJson(manifest.resources().keySet()) + ",\"description\":\"" + escape(resources) + "\"},"
                + "\"" + manifest.tool().paramsParam() + "\":{\"type\":\"object\",\"additionalProperties\":true,"
                + "\"description\":\"过滤条件与分页参数；各资源支持的字段不同，见对应 skill\"}"
                + "},\"required\":[\"" + manifest.tool().resourceParam() + "\"]}";
    }

    private String url(HttpToolManifest.Resource resource, Map<String, String> query) {
        StringBuilder url = new StringBuilder(manifest.baseUrl().trim());
        while (url.length() > 0 && url.charAt(url.length() - 1) == '/') {
            url.setLength(url.length() - 1);
        }
        url.append(resource.path());
        if (!query.isEmpty()) {
            url.append(resource.path().contains("?") ? '&' : '?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                first = false;
                url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        return url.toString();
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (manifest.auth() != null && manifest.auth().present()
                && token != null && !token.isBlank()) {
            headers.put(manifest.auth().header(), token);
        }
        return headers;
    }

    private String format(String body) {
        int maxChars = manifest.response().maxChars();
        if (body == null || body.isBlank()) {
            return "（空响应）";
        }
        if (!manifest.response().jeecg()) {
            return truncate(body, maxChars);
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            return truncate(body, maxChars);
        }
        JsonNode result = root.path("result");
        if (result.isMissingNode() || result.isNull()) {
            result = root;
        }
        if (result.isArray()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", result.size());
            out.put("records", projectAll(result));
            return truncate(toJson(out), maxChars);
        }
        if (result.isObject()) {
            JsonNode records = result.path("records");
            if (records.isArray()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("total", result.path("total").asLong(records.size()));
                out.put("records", projectAll(records));
                return truncate(toJson(out), maxChars);
            }
            return truncate(toJson(project(result)), maxChars);
        }
        return truncate(result.asText(), maxChars);
    }

    private List<JsonNode> projectAll(JsonNode array) {
        List<JsonNode> rows = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            rows.add(project(node));
        }
        return rows;
    }

    /** 剔除 jeecg 审计字段：对模型无业务价值，一条记录能省十几个 token */
    private JsonNode project(JsonNode node) {
        if (!node.isObject() || manifest.response().dropFields().isEmpty()) {
            return node;
        }
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        for (String field : manifest.response().dropFields()) {
            copy.remove(field);
        }
        return copy;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int limit = maxChars <= 0 ? 6000 : maxChars;
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n…（已截断，原长 " + text.length() + " 字符）";
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
