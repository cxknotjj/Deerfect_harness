package com.dark.javaHarness.prompt;

import com.dark.javaHarness.tool.TokenEstimator;
import com.dark.javaHarness.tool.ToolInputs;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * skill 装配管理器（prompt 动态加载·子项 1）：两段式技能暴露，与子项 6 工具 Schema
 * 延迟加载（{@link ToolLazyManager} expand_tool）同构——
 * <ul>
 *   <li>第一段：system prompt 只注入「名称 + 描述」索引段（实现 {@link SkillSectionProvider}，
 *       经 {@link PromptAssembler} 的 skill 段追加），压缩请求侧固定 token 开销；</li>
 *   <li>第二段：模型需要某技能时调用 {@link #LOAD_SKILL_TOOL_NAME load_skill(skillName)}
 *       元工具取完整 Markdown 正文（工具结果进上下文，供本轮直接遵循执行）。</li>
 * </ul>
 *
 * <p>预算口径：load_skill 不经 tracer（元工具不产生工具行噪声）、不占工具次数额度
 * （元数据操作不挤占真实工具额度，同 expand_tool）；但返回全文按 tool-result-budget
 * 同口径截断——超长技能防一次性灌爆上下文（与 ToolCallBudget 结果预算同价值）。
 *
 * <p>越权防护：元工具按构建时的「该 agent 可见技能集」校验 skillName，索引外技能拒绝加载
 * （服务端硬边界，与工具分配权限一致）。开关 app.prompt.skills.enabled（默认 true）：
 * 关闭时索引段为空、元工具不注册（回退无技能现状）。
 */
@Component
public class SkillManager implements SkillSectionProvider {

    /** 内置元工具名：按技能名加载完整说明 */
    public static final String LOAD_SKILL_TOOL_NAME = "load_skill";

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    /** load_skill 的参数定义（skillName 必填；offset 可选，用于超长正文续读） */
    static final String LOAD_SKILL_INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"skillName\":{\"type\":\"string\","
                    + "\"description\":\"要加载的技能名\"},"
                    + "\"offset\":{\"type\":\"integer\",\"description\":"
                    + "\"从正文第几个字符开始返回，默认 0；若返回内容提示继续加载，传提示中的 offset\"},"
                    + "\"attachment\":{\"type\":\"string\",\"description\":"
                    + "\"可选，技能目录内的附件文件名；传入时读取该附件而不是入口正文\"}},"
                    + "\"required\":[\"skillName\"]}";

    /** 截断标记（追加在被裁剪的技能正文末尾，提示模型内容不完整） */
    static final String TRUNCATED_SUFFIX = "…[内容已按上下文预算截断]";
    /** 索引段截断标记（技能过多/描述过长时提示模型索引不完整） */
    static final String INDEX_TRUNCATED_SUFFIX = "…[技能索引已按预算截断]";

    private final SkillRepository repository;
    /** 装配开关（app.prompt.skills.enabled） */
    private final boolean enabled;
    /** 单次 load_skill 返回正文的 token 上限（与 app.context.tool-result-budget 同口径） */
    private final int resultBudgetTokens;
    /** system prompt 技能索引段的 token 上限；0=不限制 */
    private final int maxIndexTokens;
    private final MeterRegistry meterRegistry;

    public SkillManager(SkillRepository repository,
                        @Value("${app.prompt.skills.enabled:true}") boolean enabled,
                        @Value("${app.context.tool-result-budget:5000}") int resultBudgetTokens,
                        @Value("${app.prompt.skills.max-index-tokens:0}") int maxIndexTokens,
                        MeterRegistry meterRegistry) {
        this.repository = repository;
        this.enabled = enabled;
        this.resultBudgetTokens = Math.max(resultBudgetTokens, 1);
        this.maxIndexTokens = Math.max(maxIndexTokens, 0);
        this.meterRegistry = meterRegistry;
    }

    /**
     * skill 索引段：该 agent 可见技能的「名称：描述」清单 + load_skill 使用引导；
     * 开关关闭/无可见技能返回 null（skill 段自动跳过，不产生多余空行）。
     */
    @Override
    public String provide(String agentName) {
        if (!enabled) {
            return null;
        }
        List<SkillRepository.Skill> skills = repository.skillsFor(agentName);
        if (skills.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("可用技能（需要执行某技能时，先调用 load_skill 工具")
                .append("（参数 skillName=技能名）获取完整说明再遵循执行）：");
        boolean truncated = false;
        for (SkillRepository.Skill skill : skills) {
            StringBuilder line = new StringBuilder("\n- ").append(skill.name());
            if (!skill.description().isBlank()) {
                line.append("：").append(skill.description());
            }
            if (maxIndexTokens > 0
                    && TokenEstimator.estimateTokens(sb.toString() + line + "\n" + INDEX_TRUNCATED_SUFFIX)
                    > maxIndexTokens) {
                truncated = true;
                break;
            }
            sb.append(line);
        }
        if (truncated) {
            sb.append("\n").append(INDEX_TRUNCATED_SUFFIX);
        }
        return sb.toString();
    }

    /** 技能名单：与 provide 同开关同过滤，供 llm_call_log 记录 prompt 装配名单 */
    @Override
    public List<String> skillNames(String agentName) {
        if (!enabled) {
            return List.of();
        }
        return repository.skillsFor(agentName).stream()
                .map(SkillRepository.Skill::name)
                .toList();
    }

    /**
     * load_skill 元工具：该 agent 有可见技能时返回回调（无可见技能不注册——
     * 与「空工具面不追加 expand_tool」同一原则，避免空技能面场景行为漂移）。
     */
    public Optional<ToolCallback> loadSkillTool(String agentName) {
        if (!enabled) {
            return Optional.empty();
        }
        List<SkillRepository.Skill> skills = repository.skillsFor(agentName);
        if (skills.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LoadSkillCallback(agentName, skills));
    }

    // ================================================================
    // load_skill 元工具
    // ================================================================

    /** load_skill 元工具：按技能名返回完整 Markdown 正文；可见性按构建时的技能集校验 */
    private final class LoadSkillCallback implements ToolCallback {

        /** 该 agent 可见技能集（name 小写 → skill，越权校验 + 内容来源） */
        private final Map<String, SkillRepository.Skill> skillsByName;
        private final String agentName;

        private LoadSkillCallback(String agentName, List<SkillRepository.Skill> skills) {
            this.agentName = agentName == null ? "unknown" : agentName;
            this.skillsByName = new HashMap<>();
            for (SkillRepository.Skill skill : skills) {
                skillsByName.putIfAbsent(skill.name().toLowerCase(java.util.Locale.ROOT), skill);
            }
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(LOAD_SKILL_TOOL_NAME)
                    .description("按技能名加载该技能的完整 Markdown 说明（用法/步骤/纪律）。"
                            + "技能索引中列出的技能，正式执行前先调用本工具获取全文。参数 skillName 为技能名；"
                            + "若正文超长被截断，按返回提示的 offset 再次调用可继续读取；"
                            + "返回的附件清单可用 attachment 参数读取。")
                    .inputSchema(LOAD_SKILL_INPUT_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return doLoad(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return doLoad(toolInput);
        }

        private String doLoad(String toolInput) {
            LoadRequest request = parseLoadRequest(toolInput);
            if (request == null || request.skillName() == null) {
                return "load_skill 调用失败：缺少 skillName 参数（string 类型，值为要加载的技能名），请修正后重试。";
            }
            SkillRepository.Skill skill = skillsByName.get(
                    request.skillName().toLowerCase(java.util.Locale.ROOT));
            if (skill == null) {
                // 越权防护：不在该 agent 可见技能集内的技能拒绝加载
                return "拒绝加载：技能 " + request.skillName() + " 不在当前可用技能列表中，"
                        + "只能加载技能索引中列出的技能。";
            }
            if (request.attachment() != null) {
                return readAttachment(skill, request.attachment());
            }
            String body = skill.body() == null ? "" : skill.body();
            int offset = Math.max(0, Math.min(request.offset(), body.length()));
            String attachmentSection = offset == 0 ? attachmentSection(skill) : "";
            if (offset >= body.length()) {
                return "技能 " + skill.name() + " 正文已到末尾（offset=" + offset + "）。"
                        + attachmentSection;
            }
            String prefix = offset == 0
                    ? "技能 " + skill.name() + " 完整说明：\n\n"
                    : "技能 " + skill.name() + " 完整说明（从 offset=" + offset + " 开始）：\n\n";
            String remaining = body.substring(offset);
            if (TokenEstimator.estimateTokens(prefix + remaining) <= resultBudgetTokens) {
                recordLoad(skill.name(), remaining.length(), false, offset, -1);
                return prefix + remaining + attachmentSection;
            }
            // 超长正文：按结果预算截取一段，并提示下一段的 offset（分多次取完）
            String suffixSample = TRUNCATED_SUFFIX + "（继续加载请传 offset=999999）";
            int target = Math.max(1, resultBudgetTokens
                    - TokenEstimator.estimateTokens(prefix)
                    - TokenEstimator.estimateTokens(suffixSample));
            int length = TokenEstimator.truncateToTokens(remaining, target).length();
            int nextOffset = offset + length;
            if (nextOffset >= body.length()) {
                recordLoad(skill.name(), remaining.length(), false, offset, -1);
                return prefix + remaining + attachmentSection;
            }
            recordLoad(skill.name(), length, true, offset, nextOffset);
            return prefix + remaining.substring(0, length)
                    + TRUNCATED_SUFFIX + "（继续加载请传 offset=" + nextOffset + "）"
                    + attachmentSection;
        }

        /** 技能目录内除入口文件外的附件清单（相对文件名，按名称排序） */
        private String attachmentSection(SkillRepository.Skill skill) {
            Path skillDir = skill.entry().getParent();
            if (skillDir == null) {
                return "";
            }
            try (Stream<Path> files = Files.list(skillDir)) {
                List<String> names = files.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> !name.equals(skill.entry().getFileName().toString()))
                        .sorted()
                        .toList();
                if (names.isEmpty()) {
                    return "";
                }
                StringBuilder sb = new StringBuilder(
                        "\n\n可用附件（相对技能目录，可用 load_skill 的 attachment 参数读取）：");
                for (String name : names) {
                    sb.append("\n- ").append(name);
                }
                return sb.toString();
            } catch (IOException e) {
                log.warn("[skill] 读取附件清单失败（{}）: {}", skillDir, e.toString());
                return "";
            }
        }

        /** 读取技能附件：限制在技能目录内、限制大小、按结果预算截断 */
        private String readAttachment(SkillRepository.Skill skill, String attachment) {
            Path skillDir = skill.entry().getParent();
            if (skillDir == null) {
                return "读取附件失败：技能入口路径无效。";
            }
            Path file = skillDir.resolve(attachment).normalize();
            if (!file.startsWith(skillDir.normalize())) {
                return "拒绝读取：附件路径越界（" + attachment + "）。";
            }
            if (!Files.isRegularFile(file)) {
                return "附件不存在：" + attachment;
            }
            try {
                long size = Files.size(file);
                if (size > SkillRepository.MAX_ENTRY_BYTES) {
                    return "附件过大（" + size + " bytes），拒绝读取：" + attachment;
                }
                String content = Files.readString(file);
                String prefix = "技能 " + skill.name() + " 附件 " + attachment + "：\n\n";
                if (TokenEstimator.estimateTokens(prefix + content) > resultBudgetTokens) {
                    int target = Math.max(1, resultBudgetTokens
                            - TokenEstimator.estimateTokens(prefix)
                            - TokenEstimator.estimateTokens(TRUNCATED_SUFFIX));
                    content = TokenEstimator.truncateToTokens(content, target) + TRUNCATED_SUFFIX;
                }
                log.info("[skill] attachment agent={} skill={} file={} chars={}",
                        agentName, skill.name(), attachment, content.length());
                return prefix + content;
            } catch (IOException e) {
                log.warn("[skill] 读取附件失败（{}）: {}", file, e.toString());
                return "读取附件失败：" + attachment;
            }
        }

        /** 实际加载观测：日志 + 按 agent/skill 计数的 Micrometer 指标 */
        private void recordLoad(String skillName, int chars, boolean truncated,
                                int offset, int nextOffset) {
            log.info("[skill] load agent={} skill={} chars={} truncated={} offset={} nextOffset={}",
                    agentName, skillName, chars, truncated, offset, nextOffset);
            meterRegistry.counter("harness.skill.loads",
                    "agent", agentName, "skill", skillName).increment();
            if (truncated) {
                meterRegistry.counter("harness.skill.truncated",
                        "agent", agentName, "skill", skillName).increment();
            }
        }

        private record LoadRequest(String skillName, int offset, String attachment) {
        }

        /** 解析工具入参：skillName / skill_name 与裸字符串兜底；offset/attachment 可选 */
        private LoadRequest parseLoadRequest(String toolInput) {
            String name = ToolInputs.text(toolInput, "skillName", "skill_name");
            return name == null ? null : new LoadRequest(name,
                    ToolInputs.integer(toolInput, "offset"),
                    ToolInputs.objectText(toolInput, "attachment"));
        }
    }
}
