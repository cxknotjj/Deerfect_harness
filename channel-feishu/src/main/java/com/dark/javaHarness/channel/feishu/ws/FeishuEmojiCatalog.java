package com.dark.javaHarness.channel.feishu.ws;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书表情包：识别 LLM 回复末尾的 {@code [emoji:分类]} 标记，按分类取一张本地图片。
 *
 * <p>与 QQ 的 {@code EmojiReplies} 是同一套约定、同一份数据（{@code emojis/} 目录 +
 * 索引文件），但各自实现：渠道模块之间不能互相依赖，飞书这边只需要「字节 + 文件名」，
 * 交给 {@link FeishuApiClient#uploadImage} 换成 image_key，不需要 OneBot 的 image 段。
 *
 * <p>分类取表情名前缀（{@code happy} → {@code happy_01}），同类多张随机取一；
 * {@code none}/空/未知分类不发。降级语义与 QQ 一致：模块关闭只剥标记、索引缺失自动建空文件、
 * JSON 损坏按空映射、图片缺失跳过、任何异常只记日志——{@link #pick} 永不抛出，
 * 绝不影响文本回复。
 */
final class FeishuEmojiCatalog {

    private static final Logger log = LoggerFactory.getLogger(FeishuEmojiCatalog.class);

    /** 自动创建的空映射：合法 JSON 对象，使用者照此填「表情名_序号 → 图片文件名」 */
    private static final String EMPTY_INDEX = "{\n}\n";

    /** LLM 回复末尾的情绪标记，与 QQ 同一正则 */
    private static final Pattern MARKER = Pattern.compile("\\[emoji:([a-zA-Z]+)\\]", Pattern.CASE_INSENSITIVE);

    private final FeishuProperties.Ws.Emoji cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 表情名 → 图片文件名（相对 dir）；模块关闭/加载失败降级为空映射 */
    private final Map<String, String> index;

    /** 随机源（同类多张取一 + 概率放行；包内可见：测试注入固定值做确定性断言） */
    Random random = new Random();

    FeishuEmojiCatalog(FeishuProperties.Ws.Emoji cfg) {
        this.cfg = cfg;
        // enabled=false 零行为：不读映射文件（目录更不会碰）
        this.index = cfg.isEnabled() ? loadIndex() : Map.of();
    }

    /** 剥离回复里的情绪标记：取最后一个 {@code [emoji:分类]}，返回正文与该分类 */
    static ParsedReply stripMarker(String reply) {
        if (reply == null) {
            return new ParsedReply("", null);
        }
        Matcher matcher = MARKER.matcher(reply);
        int start = -1;
        int end = -1;
        String category = null;
        while (matcher.find()) {
            category = matcher.group(1).toLowerCase(Locale.ROOT);
            start = matcher.start();
            end = matcher.end();
        }
        if (start < 0) {
            return new ParsedReply(reply.trim(), null);
        }
        return new ParsedReply((reply.substring(0, start) + reply.substring(end)).trim(), category);
    }

    /**
     * 按分类取一张表情。分类匹配表情名前缀，多个候选随机取一；
     * none/空/未知/概率未过/文件缺失/任何异常返回 null（调用方只发文本）。
     */
    Picked pick(String category) {
        if (!cfg.isEnabled() || category == null) {
            return null;
        }
        String cat = category.trim().toLowerCase(Locale.ROOT);
        if (cat.isEmpty() || "none".equals(cat)) {
            return null;
        }
        try {
            List<String> candidates = new ArrayList<>();
            for (String name : index.keySet()) {
                if (name.startsWith(cat + "_")) {
                    candidates.add(name);
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            // 概率放行：p=0 时 nextDouble() >= 0 恒成立 → 从不发送；p>=1 不摇号必发
            double probability = cfg.getProbability();
            if (probability < 1.0 && random.nextDouble() >= probability) {
                return null;
            }
            String name = candidates.size() == 1
                    ? candidates.get(0)
                    : candidates.get(random.nextInt(candidates.size()));
            return read(index.get(name), name);
        } catch (Exception e) {
            log.warn("[feishu] 表情选择异常，跳过本次表情", e);
            return null;
        }
    }

    /** 读图片文件；缺失或读取失败只 WARN 返回 null */
    private Picked read(String fileName, String name) {
        if (fileName == null || fileName.isBlank()) {
            log.warn("[feishu] 表情映射缺少图片文件名（key={}），跳过", name);
            return null;
        }
        File file = new File(cfg.getDir(), fileName);
        if (!file.isFile()) {
            log.warn("[feishu] 表情文件缺失，跳过本次表情：{}", file.getAbsolutePath());
            return null;
        }
        try {
            return new Picked(name, Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            log.warn("[feishu] 表情文件读取失败，跳过本次表情：{}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /** 启动加载映射表：缺失时自动创建空文件，JSON 损坏按空映射降级 */
    private Map<String, String> loadIndex() {
        Path path = Path.of(cfg.getIndexFile());
        if (!Files.isRegularFile(path)) {
            createEmptyIndex(path);
            return Map.of();
        }
        try {
            Map<String, String> loaded = mapper.readValue(path.toFile(),
                    new TypeReference<Map<String, String>>() {});
            log.info("[feishu] 表情映射加载完成（{} 条）：{}", loaded.size(), path.toAbsolutePath());
            return loaded;
        } catch (Exception e) {
            log.warn("[feishu] 表情映射解析失败，按空映射降级：{}", path.toAbsolutePath(), e);
            return Map.of();
        }
    }

    /**
     * 缺失时补一个空映射（连同父目录）。只在读取前调用，创建失败只 WARN——
     * 映射缺失本来就该静默降级，不能因为「建不了文件」把启动拖垮。
     */
    private void createEmptyIndex(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, EMPTY_INDEX, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            log.warn("[feishu] 表情映射文件不存在，已创建空映射：{}"
                    + "（按「表情名_序号 → 图片文件名」填写后重启生效）", path.toAbsolutePath());
        } catch (FileAlreadyExistsException e) {
            // 并发启动/他人刚创建：目标已是文件就没什么可做
            log.debug("[feishu] 表情映射文件已存在：{}", path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("[feishu] 表情映射文件无法创建，表情模块按空映射降级：{}", path.toAbsolutePath(), e);
        }
    }

    /** 取到的表情：name 用于日志，bytes 直接上传 */
    record Picked(String name, byte[] bytes) {
    }

    /** 正文与末尾情绪分类（分类 null = 无标记） */
    record ParsedReply(String text, String category) {
    }
}
