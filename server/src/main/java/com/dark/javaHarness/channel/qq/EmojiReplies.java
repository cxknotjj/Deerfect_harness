package com.dark.javaHarness.channel.qq;

import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QQ 表情包匹配器：启动时经 Jackson 加载情绪-表情映射表（napcat.emoji.index-file，
 * 表情名 → {path, tags}），按单条 chunk 文本判定是否发表情及发哪张，产出
 * OneBot v11 image 段（{@code base64://} 内联图片内容，跨机/Docker 部署可用）。
 *
 * <p>匹配规则：① tag contains 优先——chunk 文本包含某映射项任一 tag 即命中，
 * 多个命中随机取一；② 无 tag 命中且 chunk 以 ~ / ！ / ! 结尾 → 兜底 cheer-emoji；
 * ③ 均未命中不发送。probability &lt; 1.0 时按概率放行（0 = 从不发、1.0 = 必发）。
 *
 * <p>降级语义（失败隔离，对齐 NapCatApiClient「尽力而为」口径）：模块关闭（零行为，
 * 不读映射文件/目录）、映射文件缺失或 JSON 损坏（WARN + 空映射）、cheer-emoji 不在
 * 映射表（WARN 一次）、图片文件缺失（WARN + 跳过）、任何异常只记日志——
 * {@link #pickFor} 永不抛出，绝不影响文本回复主链路。
 */
public class EmojiReplies {

    private static final Logger log = LoggerFactory.getLogger(EmojiReplies.class);

    private final NapCatProperties.Emoji cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 表情名 → 映射项；模块关闭/加载失败降级为空映射 */
    private final Map<String, NapCatProperties.EmojiItem> index;

    /** 随机源（多命中取一 + 概率放行；包内可见：测试注入固定值做确定性断言） */
    Random random = new Random();

    /** cheer-emoji 缺失的 WARN 只记一次（每次句尾兜底都告警会刷屏） */
    private boolean cheerWarned;

    public EmojiReplies(NapCatProperties props) {
        this.cfg = props.getEmoji();
        // enabled=false 零行为：不读映射文件（目录更不会碰）
        this.index = cfg.isEnabled() ? loadIndex() : Map.of();
    }

    /**
     * 判定单条 chunk 应发表情：命中返回 image 段，未命中/概率未过/文件缺失/任何异常
     * 返回 null（调用方照常发文本）。内部全量 try-catch，绝不外抛。
     */
    public MessageSegment pickFor(String chunkText) {
        if (!cfg.isEnabled()) {
            return null;
        }
        try {
            NapCatProperties.EmojiItem item = pickItem(chunkText == null ? "" : chunkText);
            if (item == null) {
                return null;
            }
            // 概率放行：p=0 时 nextDouble() >= 0 恒成立 → 从不发送；(0,1) 按概率；p>=1 不摇号必发
            double p = cfg.getProbability();
            if (p < 1.0 && random.nextDouble() >= p) {
                return null;
            }
            return toImageSegment(item);
        } catch (Exception e) {
            log.warn("[napcat] 表情匹配/生成异常，跳过本次表情", e);
            return null;
        }
    }

    /**
     * 匹配一条 chunk：tag contains 优先（多命中随机取一）→ 句尾 ~/！/! 兜底 cheer-emoji
     * → null（不发送）。cheer-emoji 不在映射表时 WARN 一次并返回 null。
     */
    private NapCatProperties.EmojiItem pickItem(String text) {
        List<NapCatProperties.EmojiItem> hits = new ArrayList<>();
        for (NapCatProperties.EmojiItem item : index.values()) {
            if (item.getTags() == null || item.getPath() == null) {
                continue;
            }
            for (String tag : item.getTags()) {
                if (tag != null && !tag.isEmpty() && text.contains(tag)) {
                    hits.add(item);
                    break; // 单项任一 tag 命中即入选
                }
            }
        }
        if (!hits.isEmpty()) {
            return hits.size() == 1 ? hits.get(0) : hits.get(random.nextInt(hits.size()));
        }
        if (text.endsWith("~") || text.endsWith("！") || text.endsWith("!")) {
            NapCatProperties.EmojiItem cheer = index.get(cfg.getCheerEmoji());
            if (cheer == null) {
                if (!cheerWarned) {
                    cheerWarned = true;
                    log.warn("[napcat] cheer-emoji 不在映射表中，句尾兜底表情不可用：{}", cfg.getCheerEmoji());
                }
                return null;
            }
            return cheer;
        }
        return null;
    }

    /**
     * 文件校验 + image 段生成：文件缺失 WARN 返回 null。
     * 用 base64:// 内联图片内容（OneBot 11 标准）——NapCat 常跑在远端 Docker，file:// 指向
     * 的是 NapCat 自己的文件系统，跨机部署必然 ENOENT；base64 把内容直接塞进请求体，无此问题。
     */
    private MessageSegment toImageSegment(NapCatProperties.EmojiItem item) {
        File file = new File(cfg.getDir(), item.getPath());
        if (!file.isFile()) {
            log.warn("[napcat] 表情文件缺失，跳过本次表情：{}", file.getAbsolutePath());
            return null;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new MessageSegment("image",
                    Map.of("file", "base64://" + java.util.Base64.getEncoder().encodeToString(bytes)));
        } catch (Exception e) {
            log.warn("[napcat] 表情文件读取失败，跳过本次表情：{}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /** 启动加载映射表：文件缺失/JSON 损坏只 WARN 并按空映射降级，不影响应用启动 */
    private Map<String, NapCatProperties.EmojiItem> loadIndex() {
        File file = new File(cfg.getIndexFile());
        if (!file.isFile()) {
            log.warn("[napcat] 表情映射文件缺失，表情模块按空映射降级：{}", cfg.getIndexFile());
            return Map.of();
        }
        try {
            Map<String, NapCatProperties.EmojiItem> loaded = mapper.readValue(
                    file, new TypeReference<Map<String, NapCatProperties.EmojiItem>>() {});
            log.info("[napcat] 表情映射加载完成（{} 条）：{}", loaded.size(), cfg.getIndexFile());
            return loaded;
        } catch (Exception e) {
            log.warn("[napcat] 表情映射解析失败，按空映射降级：{}", cfg.getIndexFile(), e);
            return Map.of();
        }
    }
}
