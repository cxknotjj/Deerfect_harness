package com.dark.wms.client;

import com.dark.wms.config.WmsProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WMS OpenAPI 只读 REST 客户端（Spring RestClient 封装）。
 *
 * <ul>
 *   <li>统一 GET 请求 + 查询参数透传（jeecg 字段等值过滤 + pageNo/pageSize 分页）</li>
 *   <li>可配置鉴权头（默认 {@code X-Access-Token}），token 经环境变量注入，未配置则不带</li>
 *   <li>连接/读取超时；非 2xx 与网络异常分类为中文可读提示（{@link WmsApiException}）</li>
 *   <li>jeecg 包装响应解包：{@code result.records} 分页结构 / {@code result} 对象 / {@code result} 数组</li>
 *   <li>行投影：解包后统一剔除 jeecg 审计字段黑名单（createBy/createTime 等，见
 *       {@link #AUDIT_FIELD_BLACKLIST}），列表与详情都生效，业务字段保留</li>
 * </ul>
 * 仅封装只读 GET——写操作端点不在本类中，作为只读守护的结构性保证。
 * 错误一律抛 {@link WmsApiException}（中文可读 message），由工具层捕获转结构化文本，
 * WMS 不可用时工具调用不抛崩、MCP 会话保持存活。
 */
@Component
public class WmsRestClient {

    /** 连接超时（毫秒） */
    static final int CONNECT_TIMEOUT_MS = 5_000;
    /** 读取超时（毫秒） */
    static final int READ_TIMEOUT_MS = 15_000;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * jeecg 通用审计字段黑名单：对模型无业务价值，统一剔除以压缩工具结果体积
     * （黑名单机制而非白名单——WMS API 业务字段多，白名单会漏；后续增删只改此处）。
     */
    private static final Set<String> AUDIT_FIELD_BLACKLIST =
            Set.of("createBy", "createTime", "updateBy", "updateTime", "sysOrgCode");

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String apiBase;

    /** 生产注入构造器：类上有多个构造器，须显式标注供 Spring 选择 */
    @Autowired
    public WmsRestClient(WmsProperties props, ObjectMapper mapper) {
        this(props, mapper, defaultBuilder(props));
    }

    /**
     * 测试可注入构造器：接收外部 RestClient.Builder（如已绑定 MockRestServiceServer 的），
     * 鉴权头在本构造器内统一配置，保证测试与生产行为一致。
     */
    WmsRestClient(WmsProperties props, ObjectMapper mapper, RestClient.Builder builder) {
        this.apiBase = props.getApiBase();
        this.mapper = mapper;
        if (props.getApiToken() != null && !props.getApiToken().isBlank()) {
            builder.defaultHeader(props.getTokenHeader(), props.getApiToken());
        }
        this.restClient = builder.build();
    }

    /** 组装真实 RestClient：连接/读取超时 + 根地址（token 头由构造器统一追加） */
    private static RestClient.Builder defaultBuilder(WmsProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.getApiBase());
    }

    /**
     * 分页查询（jeecg list 端点）：解包 {@code result.records} 与 {@code result.total}。
     * 兼容 result 直接为数组（整体作为记录）或为对象无 records 字段（作为单条记录）的变体。
     */
    public WmsPage list(String path, Map<String, String> query) {
        JsonNode result = exchange(path, query);
        List<Map<String, Object>> records = new ArrayList<>();
        long total = 0;
        if (result.isArray()) {
            for (JsonNode n : result) {
                records.add(toRow(n));
            }
            total = records.size();
        } else if (result.isObject()) {
            JsonNode recordsNode = result.path("records");
            if (recordsNode.isArray()) {
                for (JsonNode n : recordsNode) {
                    records.add(toRow(n));
                }
                total = result.path("total").asLong(records.size());
            } else if (!result.isEmpty()) {
                records.add(toRow(result));
                total = 1;
            }
        }
        return new WmsPage(records, total);
    }

    /** 单条查询（queryById 类，参数名固定 {@code id}）：result 为对象；数组形态取首条兜底 */
    public Map<String, Object> one(String path, String id) {
        JsonNode result = exchange(path, Map.of("id", id == null ? "" : id));
        if (result.isArray()) {
            return result.isEmpty() ? Map.of() : toRow(result.get(0));
        }
        if (result.isObject() && !result.isEmpty()) {
            return toRow(result);
        }
        return Map.of();
    }

    /** 子表明细查询（{@code *ByMainId} 类，参数名固定 {@code id}）：result 为数组；对象形态包成单元素列表兜底 */
    public List<Map<String, Object>> children(String path, String mainId) {
        JsonNode result = exchange(path, Map.of("id", mainId == null ? "" : mainId));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (result.isArray()) {
            for (JsonNode n : result) {
                rows.add(toRow(n));
            }
        } else if (result.isObject() && !result.isEmpty()) {
            rows.add(toRow(result));
        }
        return rows;
    }

    /**
     * 行投影统一收口：JSON 节点转 Map 后剔除审计黑名单字段——
     * 列表与详情（list/one/children）都经此处，业务字段（id 等）原样保留。
     */
    private Map<String, Object> toRow(JsonNode node) {
        Map<String, Object> row = mapper.convertValue(node, MAP_TYPE);
        AUDIT_FIELD_BLACKLIST.forEach(row::remove);
        return row;
    }

    /** 统一 GET 执行：空地址前置拦截 → 请求 → 异常分类 → jeecg 信封解包 */
    private JsonNode exchange(String path, Map<String, String> query) {
        if (apiBase == null || apiBase.isBlank()) {
            throw new WmsApiException("WMS 服务地址未配置（环境变量 WMS_API_BASE 为空），无法访问 WMS");
        }
        String body;
        try {
            body = restClient.get()
                    .uri(builder -> {
                        builder.path(path);
                        query.forEach((k, v) -> {
                            if (v != null && !v.isBlank()) {
                                builder.queryParam(k, v);
                            }
                        });
                        return builder.build();
                    })
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw classifyHttp(e);
        } catch (ResourceAccessException e) {
            throw classifyNetwork(e);
        } catch (IllegalArgumentException e) {
            throw new WmsApiException("WMS 服务地址不合法或查询参数异常（请检查 WMS_API_BASE）：" + e.getMessage(), e);
        }
        return parseEnvelope(body);
    }

    /** HTTP 非 2xx 分类：鉴权失败 / 接口不存在 / 参数错误 / 服务端异常 */
    private static WmsApiException classifyHttp(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new WmsApiException("WMS 鉴权失败（HTTP " + status + "）：请检查 WMS_API_TOKEN 与鉴权头名称配置", e);
        }
        if (status == 404) {
            return new WmsApiException("WMS 接口不存在（HTTP 404）：请确认 WMS_API_BASE 与端点路径", e);
        }
        if (status == 400 || status == 405 || status == 422) {
            return new WmsApiException("WMS 拒绝请求（HTTP " + status + "）：查询参数可能有误", e);
        }
        if (status >= 500) {
            return new WmsApiException("WMS 服务异常（HTTP " + status + "）：请稍后重试", e);
        }
        return new WmsApiException("WMS 返回异常状态（HTTP " + status + "）", e);
    }

    /** 网络异常分类：读取超时 / 连接不可达 */
    private static WmsApiException classifyNetwork(ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException) {
            return new WmsApiException("WMS 请求超时：WMS 响应过慢或网络不稳定，请稍后重试", e);
        }
        String reason = cause == null ? e.getMessage() : cause.getMessage();
        return new WmsApiException("WMS 服务不可达：连接失败（" + reason + "），请确认 WMS_API_BASE 与 WMS 运行状态", e);
    }

    /** jeecg 信封解包：success=false（或 code!=200 且无 success 字段）视为业务失败；result 空缺返回空节点 */
    private JsonNode parseEnvelope(String body) {
        if (body == null || body.isBlank()) {
            throw new WmsApiException("WMS 返回了空响应");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new WmsApiException("WMS 返回了非预期格式（非 JSON）", e);
        }
        int code = root.path("code").asInt(-1);
        boolean success = root.path("success").asBoolean(code == 200);
        if (!success) {
            String message = root.path("message").asText("");
            throw new WmsApiException("WMS 返回业务失败（code=" + code + "）：" + message);
        }
        JsonNode result = root.get("result");
        return result == null || result.isNull() ? mapper.createObjectNode() : result;
    }
}
