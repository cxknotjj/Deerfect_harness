package com.dark.javaHarness.prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * skill Markdown 技能库（prompt 动态加载·子项 1）：扫描技能目录下的技能子目录，
 * 解析入口文件的 front-matter 元数据与正文，按入口文件 mtime 热重载——放入/修改/删除
 * 技能目录，下一请求即生效，无需重启。
 *
 * <p>目录结构（每个技能一个子目录，入口文件二选一）：
 * <pre>
 * skills/
 *   &lt;skill-name&gt;/
 *     SKILL.md              # 推荐入口
 *     或 &lt;skill-name&gt;.md    # 同名声目录命名入口
 *     ...                   # 其他文件视为技能资源，不参与索引
 * </pre>
 *
 * <p>入口文件格式（Markdown + 简单 front-matter，逐行 key: value 手写解析不引依赖；
 * 当前只支持扁平 key: value，不支持 YAML 列表/多行值，需要时再引入 YAML）：
 * <pre>
 * ---
 * name: web-research          # 可选，缺省=技能子目录名
 * description: 一句话用途      # 可选，缺省=正文首个非空行
 * agents: researcher, general # 可选，缺省=全部 agent 可见
 * ---
 * 正文（完整技能说明，load_skill 返回的就是这段）
 * </pre>
 *
 * <p>旧版「skills/ 顶层平铺 .md」已移除：顶层文件不再参与扫描。
 *
 * <p>容错：目录缺失视为空技能面（info 一次）；坏文件/坏 front-matter warn 跳过不抛，
 * 且失败结果按 mtime 缓存（文件不变不重试、不重复刷 warn）。
 */
@Component
public class SkillRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillRepository.class);

    /** 技能条目：agents 为 null/空表示对所有 agent 可见；entry 为入口文件绝对路径 */
    public record Skill(String name, String description, Set<String> agents, String body, Path entry) {

        /** 该 agent 是否可见（agents 未声明 = 全部可见） */
        public boolean visibleTo(String agentName) {
            return agents == null || agents.isEmpty()
                    || (agentName != null && agents.contains(agentName.toLowerCase(Locale.ROOT)));
        }
    }

    /** 只读校验视图：供 /api/skills 与启动汇总使用；error 为 null 表示无问题 */
    public record SkillInfo(String name, String description, Set<String> agents,
                            String entryFile, long size, String error) {
    }

    private record ParseResult(Skill skill, String diagnostic) {
    }

    /** 缓存条目：mtime + size 指纹与解析结果（skill 为 null 表示该指纹下解析失败，不重试不重复 warn） */
    private record Cached(long mtime, long size, Skill skill) {
    }

    /** 单入口文件大小上限（校验视图先报告，P2-4 再决定是否拒绝解析） */
    static final long MAX_ENTRY_BYTES = 1024 * 1024L;

    /** 扫描快照：短 TTL 内复用，避免单请求里 provide/skillNames/loadSkillTool 重复扫目录 */
    private record Snapshot(List<Skill> skills, long createdAtNanos) {
    }

    private final Path dir;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    /** 已告警过的重名入口（相对路径），避免每次扫描重复刷同一冲突 */
    private final Set<String> warnedConflicts = ConcurrentHashMap.newKeySet();
    private final long scanCacheNanos;
    /** 单调时钟（纳秒）：默认 System::nanoTime，测试注入可推进的实现以验证 TTL，无需 sleep */
    private final java.util.function.LongSupplier nanoClock;
    private final Object scanLock = new Object();
    private volatile Snapshot snapshot;

    // 显式标注：类里还有一个测试用的包可见构造器，Spring 需要在多个候选里明确选这个
    @Autowired
    public SkillRepository(@Value("${app.prompt.skills.dir:skills}") String dir,
                           @Value("${app.prompt.skills.scan-cache-ms:200}") long scanCacheMs) {
        this(dir, scanCacheMs, System::nanoTime);
    }

    /** 测试用：注入可控纳秒时钟，TTL 到期靠推进时钟验证（不用 sleep 等真实时间） */
    SkillRepository(String dir, long scanCacheMs, java.util.function.LongSupplier nanoClock) {
        this.dir = Path.of(dir == null || dir.isBlank() ? "skills" : dir.trim());
        this.scanCacheNanos = Math.max(0, scanCacheMs) * 1_000_000L;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        if (!Files.isDirectory(this.dir)) {
            log.info("[skill] 技能目录不存在（{}），技能面为空——创建目录并为每个技能建子目录（SKILL.md）即可热装配",
                    this.dir.toAbsolutePath());
        } else {
            validateOnStartup();
        }
    }

    /** 启动时做一次全量校验汇总：可用技能数 + 每个问题的文件与原因 */
    private void validateOnStartup() {
        List<SkillInfo> infos = inspect();
        long issues = infos.stream().filter(info -> info.error() != null).count();
        if (issues == 0) {
            log.info("[skill] 启动校验完成：{} 个技能", infos.size());
            return;
        }
        log.warn("[skill] 启动校验：{} 个目录，{} 个问题", infos.size(), issues);
        infos.stream()
                .filter(info -> info.error() != null)
                .forEach(info -> log.warn("[skill] 校验 {}：{}", info.entryFile(), info.error()));
    }

    /** 某 agent 可见的技能清单（按名称排序）；目录缺失/无可见技能返回空表 */
    public List<Skill> skillsFor(String agentName) {
        List<Skill> visible = new ArrayList<>();
        for (Skill skill : scan()) {
            if (skill.visibleTo(agentName)) {
                visible.add(skill);
            }
        }
        return List.copyOf(visible);
    }

    /** 按名取技能（忽略大小写；可见性校验——越权名视为不存在） */
    public Optional<Skill> find(String name, String agentName) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.trim();
        return scan().stream()
                .filter(skill -> skill.name().equalsIgnoreCase(key) && skill.visibleTo(agentName))
                .findFirst();
    }

    /** 短 TTL 快照：单请求内多次 skillsFor/find 复用同一扫描结果；过期后重新扫描 */
    private List<Skill> scan() {
        if (scanCacheNanos <= 0) {
            return scanUncached();
        }
        Snapshot current = snapshot;
        if (current != null && nanoClock.getAsLong() - current.createdAtNanos() < scanCacheNanos) {
            return current.skills();
        }
        synchronized (scanLock) {
            current = snapshot;
            if (current != null && nanoClock.getAsLong() - current.createdAtNanos() < scanCacheNanos) {
                return current.skills();
            }
            List<Skill> skills = scanUncached();
            snapshot = new Snapshot(skills, nanoClock.getAsLong());
            return skills;
        }
    }

    /** 扫描技能目录下的子目录：mtime+size 变化才重新解析（热重载）；顶层 .md 不再参与扫描 */
    private List<Skill> scanUncached() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Skill> out = new ArrayList<>();
        Set<String> scannedKeys = new HashSet<>();
        Map<String, Path> seenNames = new HashMap<>();
        boolean complete = true;
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(skillDir -> {
                        Path entry = entryFile(skillDir);
                        if (entry == null) {
                            return;
                        }
                        String key = cacheKey(entry);
                        scannedKeys.add(key);
                        Skill skill = load(skillDir, entry);
                        if (skill == null) {
                            return;
                        }
                        Path previous = seenNames.putIfAbsent(skill.name().toLowerCase(Locale.ROOT), entry);
                        if (previous != null) {
                            // 重名技能保留目录名排序第一条，后续条目跳过并告警一次
                            if (warnedConflicts.add(key)) {
                                log.warn("[skill] 技能名冲突：'{}' 已由 {} 提供，跳过 {}",
                                        skill.name(), previous, entry);
                            }
                            return;
                        }
                        out.add(skill);
                    });
        } catch (IOException e) {
            complete = false;
            log.warn("[skill] 扫描技能目录失败（{}）: {}", dir, e.toString());
        }
        if (complete) {
            // 清理本次未出现的入口缓存：删除/改名/移出技能目录后不残留旧正文
            cache.keySet().removeIf(key -> !scannedKeys.contains(key));
        }
        out.sort(Comparator.comparing(Skill::name));
        return out;
    }

    /** 技能入口文件：优先 SKILL.md，其次 `<子目录名>.md`；找不到视为非技能目录 */
    private static Path entryFile(Path skillDir) {
        Path standard = skillDir.resolve("SKILL.md");
        if (Files.isRegularFile(standard)) {
            return standard;
        }
        Path named = skillDir.resolve(skillDir.getFileName().toString() + ".md");
        return Files.isRegularFile(named) ? named : null;
    }

    /** 单入口文件加载：mtime+size 未变走缓存（含失败缓存），变化才重新解析 */
    private Skill load(Path skillDir, Path entry) {
        long mtime;
        long size;
        try {
            BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
            mtime = attrs.lastModifiedTime().toMillis();
            size = attrs.size();
        } catch (IOException e) {
            log.warn("[skill] 读取文件属性失败，跳过 {}: {}", entry, e.toString());
            return null;
        }
        String key = cacheKey(entry);
        Cached cached = cache.get(key);
        if (cached != null && cached.mtime() == mtime && cached.size() == size) {
            return cached.skill();
        }
        Skill parsed = parseResult(entry, skillDir.getFileName().toString()).skill();
        cache.put(key, new Cached(mtime, size, parsed));
        if (parsed != null) {
            log.info("[skill] 已加载技能 '{}'（{}）", parsed.name(), key);
        }
        return parsed;
    }

    private String cacheKey(Path entry) {
        return dir.relativize(entry).toString();
    }

    /** 只读校验：扫描所有技能子目录，返回可用/不可用项及原因（不经过短 TTL 快照） */
    public List<SkillInfo> inspect() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SkillInfo> out = new ArrayList<>();
        Map<String, Path> seenNames = new HashMap<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(skillDir -> {
                        Path entry = entryFile(skillDir);
                        if (entry == null) {
                            out.add(new SkillInfo(skillDir.getFileName().toString(), "",
                                    null, dir.relativize(skillDir).toString(), 0,
                                    "缺少入口文件（SKILL.md 或 <目录名>.md）"));
                            return;
                        }
                        long size = 0;
                        try {
                            size = Files.size(entry);
                        } catch (IOException e) {
                            log.warn("[skill] 校验读取大小失败（{}）: {}", entry, e.toString());
                        }
                        ParseResult parsed = parseResult(entry, skillDir.getFileName().toString());
                        String error = parsed.diagnostic();
                        String name = parsed.skill() == null
                                ? skillDir.getFileName().toString()
                                : parsed.skill().name();
                        if (parsed.skill() != null) {
                            Path previous = seenNames.putIfAbsent(
                                    name.toLowerCase(Locale.ROOT), entry);
                            if (previous != null) {
                                error = joinDiagnostic(error,
                                        "技能名与 " + previous + " 重复，已跳过");
                            }
                        }
                        out.add(new SkillInfo(name,
                                parsed.skill() == null ? "" : parsed.skill().description(),
                                parsed.skill() == null ? null : parsed.skill().agents(),
                                cacheKey(entry), size, error));
                    });
        } catch (IOException e) {
            log.warn("[skill] 校验技能目录失败（{}）: {}", dir, e.toString());
        }
        return List.copyOf(out);
    }

    /** 解析入口文件：front-matter（--- 包围，逐行 key: value）+ 正文；name 缺省取技能目录名 */
    private static ParseResult parseResult(Path file, String fallbackName) {
        try {
            long size = Files.size(file);
            if (size > MAX_ENTRY_BYTES) {
                log.warn("[skill] 技能文件过大，拒绝解析（{}，{} bytes）", file, size);
                return new ParseResult(null,
                        "文件过大（" + size + " bytes > " + MAX_ENTRY_BYTES + "）");
            }
            String text = Files.readString(file);
            boolean hadBom = !text.isEmpty() && text.charAt(0) == '\uFEFF';
            if (hadBom) {
                text = text.substring(1);
            }
            text = text.stripLeading();
            Map<String, String> meta = new HashMap<>();
            String body = text;
            boolean unclosed = false;
            if (text.startsWith("---")) {
                String[] lines = text.split("\r?\n", -1);
                int close = -1;
                for (int i = 1; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.equals("---")) {
                        close = i;
                        break;
                    }
                    int idx = line.indexOf(':');
                    if (idx > 0) {
                        meta.putIfAbsent(line.substring(0, idx).trim().toLowerCase(Locale.ROOT),
                                line.substring(idx + 1).trim());
                    }
                }
                if (close > 0) {
                    body = String.join("\n", Arrays.copyOfRange(lines, close + 1, lines.length)).strip();
                } else {
                    // 未闭合的 front-matter：按无 front-matter 处理，整文件为正文
                    log.warn("[skill] front-matter 未闭合，按正文处理（{}）", file);
                    meta.clear();
                    unclosed = true;
                }
            }
            String name = valueOf(meta, "name", fallbackName);
            String description = valueOf(meta, "description", firstLineOf(body));
            String agentsRaw = valueOf(meta, "agents", "");
            Set<String> agents = agentsRaw.isBlank() ? null
                    : Arrays.stream(agentsRaw.split("[,，\\s]+"))
                            .map(s -> s.trim().toLowerCase(Locale.ROOT))
                            .filter(s -> !s.isEmpty())
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            String diagnostic = null;
            if (hadBom) {
                diagnostic = joinDiagnostic(diagnostic, "检测到 UTF-8 BOM（已自动清理）");
            }
            if (unclosed) {
                diagnostic = joinDiagnostic(diagnostic, "front-matter 未闭合，已按正文处理");
            }
            if (body.isBlank()) {
                diagnostic = joinDiagnostic(diagnostic, "正文为空");
            }
            return new ParseResult(new Skill(name, description == null ? "" : description,
                    agents, body, file), diagnostic);
        } catch (Exception e) {
            log.warn("[skill] 解析技能文件失败（{}）: {}", file, e.toString());
            return new ParseResult(null, "解析失败: " + e.getMessage());
        }
    }

    private static String joinDiagnostic(String current, String addition) {
        return current == null || current.isBlank() ? addition : current + "; " + addition;
    }

    private static String valueOf(Map<String, String> meta, String key, String fallback) {
        String value = meta.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 正文首个非空行（description 缺省值）；无内容返回空串 */
    private static String firstLineOf(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        for (String line : body.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }
}
