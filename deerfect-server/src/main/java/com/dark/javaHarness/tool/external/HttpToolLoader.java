package com.dark.javaHarness.tool.external;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * 外部 HTTP 工具的装载器：扫目录、解析清单、建工具，支持热重载。
 *
 * <p>这是「集成类工具外置」的落点——接一个新的 REST 系统只需要放一份 yaml，
 * 不写 Java、不改 {@code ToolAssignments}、不重打包。与 MCP 的分工：
 * MCP 给第三方/多语言服务，这个给自家接口（路径与参数都被清单约束，模型不能自由出站）。
 *
 * <p>坏清单不会中断启动：收集问题、跳过该文件、在 {@code /api/capabilities} 里暴露。
 * 外部文件是运维资产，一个手写错误不该让整个服务起不来；需要严格时把
 * {@code app.tools.http.fail-fast} 打开即可。
 */
@Component
public class HttpToolLoader {

    private static final Logger log = LoggerFactory.getLogger(HttpToolLoader.class);
    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)(?::([^}]*))?}");

    private final boolean enabled;
    private final Path dir;
    private final boolean failFast;
    private final ObjectMapper mapper;
    private final HttpTransport transport;

    private volatile Snapshot snapshot = Snapshot.empty();

    @Autowired
    public HttpToolLoader(@Value("${app.tools.http.enabled:true}") boolean enabled,
                          @Value("${app.tools.http.dir:tools/http}") String dir,
                          @Value("${app.tools.http.fail-fast:false}") boolean failFast,
                          ObjectMapper mapper) {
        this(enabled, Path.of(dir), failFast, mapper, new JdkHttpTransport());
    }

    /** 可注入传输/目录的构造器：单测与嵌入场景用（Spring 走上面那个） */
    public HttpToolLoader(boolean enabled, Path dir, boolean failFast, ObjectMapper mapper, HttpTransport transport) {
        this.enabled = enabled;
        this.dir = dir;
        this.failFast = failFast;
        this.mapper = mapper;
        this.transport = transport;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 重新扫描目录并原子替换快照。
     *
     * @return 问题列表（空表示全部解析成功）；fail-fast 打开时解析出问题直接抛
     */
    public synchronized List<String> reload() {
        if (!enabled) {
            snapshot = Snapshot.empty();
            log.info("[http工具] 已关闭（app.tools.http.enabled=false）");
            return List.of();
        }
        if (!Files.isDirectory(dir)) {
            snapshot = Snapshot.empty();
            log.info("[http工具] 目录不存在（{}），外部工具面为空", dir.toAbsolutePath().normalize());
            return List.of();
        }

        List<String> problems = new ArrayList<>();
        Map<String, List<HttpToolManifest>> byPack = new LinkedHashMap<>();
        Map<String, HttpToolCallback> byTool = new LinkedHashMap<>();
        List<HttpToolManifest> manifests = new ArrayList<>();
        Set<String> toolNames = new LinkedHashSet<>();

        for (Path file : files()) {
            HttpToolManifest manifest;
            try {
                manifest = parse(file);
            } catch (Exception e) {
                problems.add(file.getFileName() + "：" + e.getMessage());
                continue;
            }
            if (!toolNames.add(manifest.tool().name())) {
                problems.add(file.getFileName() + "：工具名重复 " + manifest.tool().name());
                continue;
            }
            byPack.computeIfAbsent(manifest.pack(), key -> new ArrayList<>()).add(manifest);
            byTool.put(manifest.tool().name(), new HttpToolCallback(manifest, transport, tokenOf(manifest), mapper));
            manifests.add(manifest);
        }

        for (String problem : problems) {
            log.error("[http工具] {}", problem);
        }
        if (failFast && !problems.isEmpty()) {
            throw new IllegalStateException("外部 HTTP 工具清单有误：" + problems);
        }

        snapshot = new Snapshot(byPack, byTool, manifests, problems);
        int resources = manifests.stream().mapToInt(m -> m.resources().size()).sum();
        log.info("[http工具] 装载 {} 个工具 / {} 个资源（目录 {}，问题 {} 条）",
                manifests.size(), resources, dir, problems.size());
        return problems;
    }

    /** 按 pack 名或工具名取回调：pack 名用于 agent.tools 配置，工具名用于精确点名 */
    public List<ToolCallback> callbacks(String token) {
        if (token == null || token.isBlank()) {
            return List.of();
        }
        Snapshot current = snapshot;
        List<HttpToolManifest> pack = current.byPack().get(token);
        if (pack != null) {
            List<ToolCallback> callbacks = new ArrayList<>(pack.size());
            for (HttpToolManifest manifest : pack) {
                HttpToolCallback callback = current.byTool().get(manifest.tool().name());
                if (callback != null) {
                    callbacks.add(callback);
                }
            }
            return callbacks;
        }
        HttpToolCallback exact = current.byTool().get(token);
        return exact == null ? List.of() : List.of(exact);
    }

    public List<HttpToolManifest> manifests() {
        return snapshot.manifests();
    }

    public List<String> problems() {
        return snapshot.problems();
    }

    public Path directory() {
        return dir;
    }

    public boolean enabled() {
        return enabled;
    }

    private List<Path> files() {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches(".*\\.(yaml|yml)"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[http工具] 目录读取失败（{}）：{}", dir, e.toString());
            return List.of();
        }
    }

    private HttpToolManifest parse(Path file) throws IOException {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            root = asMap(loaded);
        }
        if (root.isEmpty()) {
            throw new IllegalArgumentException("空文件");
        }
        String pack = str(root.get("pack"));
        if (pack.isBlank()) {
            throw new IllegalArgumentException("缺少 pack");
        }

        Map<String, Object> toolNode = asMap(root.get("tool"));
        String toolName = str(toolNode.get("name"));
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("缺少 tool.name");
        }
        String description = str(toolNode.get("description"));
        if (description.isBlank()) {
            description = "查询外部系统（资源与参数见对应 skill）";
        }
        String resourceParam = str(toolNode.get("resource-param"));
        String paramsParam = str(toolNode.get("params-param"));
        HttpToolManifest.ToolSpec tool = new HttpToolManifest.ToolSpec(
                toolName, description,
                resourceParam.isBlank() ? "resource" : resourceParam,
                paramsParam.isBlank() ? "params" : paramsParam);

        Map<String, Object> resourcesNode = asMap(root.get("resources"));
        if (resourcesNode.isEmpty()) {
            throw new IllegalArgumentException("resources 不能为空");
        }
        Map<String, HttpToolManifest.Resource> resources = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : resourcesNode.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> node = asMap(entry.getValue());
            String path = str(node.get("path"));
            if (path.isBlank() || !path.startsWith("/")) {
                throw new IllegalArgumentException("资源 " + name + " 的 path 必须以 / 开头");
            }
            List<String> params = asList(node.get("params"));
            List<String> requires = asList(node.get("requires"));
            if (!params.containsAll(requires)) {
                throw new IllegalArgumentException("资源 " + name + " 的 requires 必须是 params 的子集");
            }
            resources.put(name, new HttpToolManifest.Resource(
                    path, params, requires, asStringMap(node.get("fixed")), str(node.get("summary"))));
        }

        Map<String, Object> authNode = asMap(root.get("auth"));
        HttpToolManifest.Auth auth = new HttpToolManifest.Auth(
                resolve(str(authNode.get("header"))), str(authNode.get("token-env")));

        Map<String, Object> responseNode = asMap(root.get("response"));
        String format = str(responseNode.get("format"));
        int maxChars = asInt(responseNode.get("max-chars"), 6000);
        HttpToolManifest.ResponseShape response = new HttpToolManifest.ResponseShape(
                format.isBlank() ? "raw" : format,
                asList(responseNode.get("drop-fields")),
                maxChars);

        int timeoutMs = asInt(root.get("timeout-ms"), 15000);
        timeoutMs = Math.max(1000, Math.min(timeoutMs, 120_000));

        String rawBaseUrl = str(root.get("base-url"));
        return new HttpToolManifest(pack, file, resolve(rawBaseUrl), rawBaseUrl, auth,
                timeoutMs, response, tool, resources);
    }

    private static String tokenOf(HttpToolManifest manifest) {
        if (manifest.auth() == null || manifest.auth().tokenEnv() == null) {
            return null;
        }
        String env = manifest.auth().tokenEnv().trim();
        if (env.isEmpty()) {
            return null;
        }
        String value = System.getenv(env);
        return value == null || value.isBlank() ? null : value;
    }

    /** 只对 base-url / header 名做 ${ENV:default} 替换；token 只认环境变量名，不把凭据写进文件 */
    static String resolve(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        Matcher matcher = ENV.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = System.getenv(matcher.group(1));
            if (value == null) {
                value = matcher.group(2) == null ? "" : matcher.group(2);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Map<String, String> asStringMap(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        asMap(value).forEach((key, val) -> out.put(String.valueOf(key), String.valueOf(val)));
        return out;
    }

    private static List<String> asList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item).trim());
            }
        }
        return out;
    }

    private record Snapshot(Map<String, List<HttpToolManifest>> byPack,
                            Map<String, HttpToolCallback> byTool,
                            List<HttpToolManifest> manifests,
                            List<String> problems) {

        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), List.of(), List.of());
        }
    }
}
