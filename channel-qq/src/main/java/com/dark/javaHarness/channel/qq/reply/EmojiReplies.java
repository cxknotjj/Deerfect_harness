package com.dark.javaHarness.channel.qq.reply;

import com.dark.javaHarness.channel.qq.client.NapCatProperties;
import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QQ 表情包选择器：启动时经 Jackson 加载 表情名 → 图片文件 映射（napcat.emoji.index-file），
 * 按 LLM 输出的分类标记在同类表情中随机取一张，产出 OneBot v11 image 段
 * （{@code base64://} 内联图片内容，跨机/Docker 部署可用）。
 *
 * <p>分类取自表情名前缀：{@code happy} → {@code happy_01}、{@code happy_02}…；多个候选随机取一。
 * {@code none}/空/未知分类不发送；probability &lt; 1.0 时按概率放行。
 *
 * <p>降级语义（失败隔离，对齐 NapCatApiClient「尽力而为」口径）：模块关闭（零行为，
 * 不读映射文件/目录）、映射文件缺失或 JSON 损坏（WARN + 空映射）、图片文件缺失（WARN + 跳过）、
 * 任何异常只记日志——{@link #pickByCategory} 永不抛出，绝不影响文本回复主链路。
 */
public class EmojiReplies {

    private static final Logger log = LoggerFactory.getLogger(EmojiReplies.class);

    private final NapCatProperties.Emoji cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 表情名 → 图片文件名（相对 dir）；模块关闭/加载失败降级为空映射 */
    private final Map<String, String> index;

    /** 随机源（同类多张取一 + 概率放行；包内可见：测试注入固定值做确定性断言） */
    Random random = new Random();

    public EmojiReplies(NapCatProperties props) {
        this.cfg = props.getEmoji();
        // enabled=false 零行为：不读映射文件（目录更不会碰）
        this.index = cfg.isEnabled() ? loadIndex() : Map.of();
    }

    /**
     * 按分类取一张表情：分类匹配表情名前缀（如 happy → happy_01…），多个候选随机取一。
     * none/空/未知分类/概率未过/文件缺失/任何异常返回 null（调用方照常发文本）。
     */
    public MessageSegment pickByCategory(String category) {
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
            // 概率放行：p=0 时 nextDouble() >= 0 恒成立 → 从不发送；(0,1) 按概率；p>=1 不摇号必发
            double p = cfg.getProbability();
            if (p < 1.0 && random.nextDouble() >= p) {
                return null;
            }
            String name = candidates.size() == 1
                    ? candidates.get(0)
                    : candidates.get(random.nextInt(candidates.size()));
            return toImageSegment(index.get(name));
        } catch (Exception e) {
            log.warn("[napcat] 表情选择/生成异常，跳过本次表情", e);
            return null;
        }
    }

    /**
     * 文件校验 + image 段生成：文件缺失 WARN 返回 null。
     * 用 base64:// 内联图片内容（OneBot 11 标准）——NapCat 常跑在远端 Docker，file:// 指向
     * 的是 NapCat 自己的文件系统，跨机部署必然 ENOENT；base64 把内容直接塞进请求体，无此问题。
     */
    private MessageSegment toImageSegment(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            log.warn("[napcat] 表情映射缺少图片文件名，跳过本次表情");
            return null;
        }
        File file = new File(cfg.getDir(), fileName);
        if (!file.isFile()) {
            log.warn("[napcat] 表情文件缺失，跳过本次表情：{}", file.getAbsolutePath());
            return null;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new MessageSegment("image",
                    Map.of("file", "base64://" + Base64.getEncoder().encodeToString(bytes)));
        } catch (Exception e) {
            log.warn("[napcat] 表情文件读取失败，跳过本次表情：{}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /** 启动加载映射表：文件缺失/JSON 损坏只 WARN 并按空映射降级，不影响应用启动 */
    private Map<String, String> loadIndex() {
        File file = new File(cfg.getIndexFile());
        if (!file.isFile()) {
            log.warn("[napcat] 表情映射文件缺失，表情模块按空映射降级：{}", cfg.getIndexFile());
            return Map.of();
        }
        try {
            Map<String, String> loaded = mapper.readValue(
                    file, new TypeReference<Map<String, String>>() {});
            log.info("[napcat] 表情映射加载完成（{} 条）：{}", loaded.size(), cfg.getIndexFile());
            return loaded;
        } catch (Exception e) {
            log.warn("[napcat] 表情映射解析失败，按空映射降级：{}", cfg.getIndexFile(), e);
            return Map.of();
        }
    }
}
