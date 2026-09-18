package com.dark.javaHarness.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 知识库 BM25 内存索引：从 PG 向量表（Spring AI PgVectorStore 自建表）拉取 chunk
 * （id/content/metadata）构建倒排索引，为混合检索提供 BM25 leg——关键词、编号、
 * 专有名词类查询的字面精确匹配，弥补向量相似度把字面信息抹掉、对这类查询召回差的短板。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>为什么内存索引</b>：BM25 评分需要 df、文档长度等全集统计量，逐次检索现算
 *       等于每次全表扫描 PG——内存倒排索引一次构建、多次检索，把成本摊薄到检索路径；
 *       且按 kb 惰性分库缓存（volatile 不可变快照 + synchronized 构建 + 脏标记），
 *       首次检索才拉取对应 kb 的 chunk，未检索的库零开销、未绑定库不拉取。</li>
 *   <li><b>为什么字符 bigram</b>：PG 侧无中文分词扩展（zhparser 需换镜像，过重），
 *       中文按连续段切字符 bigram（「知识库」→ 知识/识库）无需分词器即可近似词匹配；
 *       ASCII 按小写词元、数字+字母混合段整体保留（v4 不拆散）。代价是索引项偏多、
 *       偶发跨词噪声命中，由 BM25 排序与 score&gt;0 过滤兜底。</li>
 *   <li><b>规模护栏</b>：chunk 总数超过 bm25-max-chunks（0 = 不限，项目「0 = 关闭该
 *       约束」口径）时跳过构建并 warn，检索返回空表——BM25 leg 是融合检索的增强路
 *       而非义务，护栏防超大库把堆内存吃穿，缺位时向量检索照常工作。</li>
 * </ul>
 *
 * <p>线程安全：索引缓存整体为不可变快照（volatile 发布，构建在 monitor 内完成），
 * {@link #invalidate()} 仅置脏标记，重建发生在下次 search 前按需执行（懒重建，
 * 不阻塞摄取路径）；PG 异常按知识包降级口径返回空表，不阻断主链路。
 */
public class KnowledgeBm25Index {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBm25Index.class);

    /** BM25 饱和度参数 k1（词频增益上限，业界常规取值） */
    private static final double K1 = 1.2;

    /** BM25 长度归一参数 b（1 = 完全按文档长度归一，业界常规取值） */
    private static final double B = 0.75;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate vectorJdbcTemplate;
    private final String tableName;

    /** 规模护栏上限（chunk 总数超过则不建索引，检索退化为纯向量）；0 = 不限 */
    private final int maxChunks;

    /**
     * 索引缓存：归一化 kb 列表（去空白去重排序后逗号拼接；空串 = 全库）→ 该 kb 组合
     * 的索引快照。volatile + 不可变快照（Map.copyOf）保证构建完成后对检索线程安全发布；
     * 重建在 {@link #buildMonitor} 内串行，避免并发重复拉取 PG。
     */
    private volatile Map<String, KbIndex> indexByKbKey = Map.of();

    /** 脏标记：invalidate() 置位，下次检索前清空全部缓存快照并按需重建（懒重建） */
    private volatile boolean stale = true;

    /** 索引构建锁：同一时刻至多一个线程在拉取 PG / 建索引 */
    private final Object buildMonitor = new Object();

    /** 护栏告警去重：超限期间多次检索只 warn 一次，护栏恢复（重建成功）后复位 */
    private volatile boolean guardrailWarned;

    public KnowledgeBm25Index(JdbcTemplate vectorJdbcTemplate, String tableName, int maxChunks) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.tableName = tableName;
        this.maxChunks = maxChunks;
    }

    /**
     * BM25 检索：查询与文档共用同一分词器（中文 bigram / ASCII 词元），对查询中每个
     * 词元在包含它的文档上按 idf × tf 饱和度累加得分，score&gt;0 的命中按分数降序输出。
     *
     * @param query 查询文本（null/空白或分词后无词元返回空表）
     * @param kbs   知识库过滤（对应 chunk metadata.kb）；null/空 = 不限，检索全部库
     * @param limit 返回条数上限；&lt;=0 = 不限（对齐项目「0 = 关闭该约束」口径）
     * @return 命中列表（可能为空：无命中 / 索引未建 / 护栏触发 / PG 异常降级）
     */
    public List<Bm25Hit> search(String query, List<String> kbs, int limit) {
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<String> normalizedKbs = normalizeKbs(kbs);
        try {
            KbIndex index = indexFor(cacheKey(normalizedKbs), normalizedKbs);
            return index == null ? List.of() : index.search(terms, limit);
        } catch (Exception e) {
            // 索引构建/查询失败降级空表：BM25 是增强路，PG 抖动不阻断主链路（知识包降级口径）
            log.warn("[knowledge] BM25 检索失败（退化为空结果）: {}", rootMessage(e));
            return List.of();
        }
    }

    /**
     * 索引失效：sync/delete 改动底层数据后由服务层调用（接线由后续任务完成），仅置脏
     * 标记不重建——重建发生在下次 search 前，不阻塞摄取路径。
     */
    public void invalidate() {
        stale = true;
    }

    /* ---------------- 分词器（包可见供测试） ---------------- */

    /**
     * 查询/文档共用分词器：小写化后，ASCII 连续 [a-z0-9]+ 切词元（数字+字母混合段
     * 如 v4 按整体词元保留）、CJK 连续段切字符 bigram（段长为 1 则单字）、其余字符
     * （标点/空白/emoji 等）作分隔符忽略。
     */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (isAsciiWord(cp)) {
                flushCjk(cjk, tokens);
                ascii.appendCodePoint(cp);
            } else if (isCjk(cp)) {
                flushAscii(ascii, tokens);
                cjk.appendCodePoint(cp);
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private static void flushAscii(StringBuilder buf, List<String> out) {
        if (buf.length() > 0) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }

    /** CJK 连续段出词：长度 1 输出单字，长度 &gt;= 2 输出重叠 bigram（n 个字 → n-1 个词元） */
    private static void flushCjk(StringBuilder buf, List<String> out) {
        int len = buf.length();
        if (len == 0) {
            return;
        }
        if (len == 1) {
            out.add(buf.toString());
        } else {
            for (int i = 0; i + 2 <= len; i++) {
                out.add(buf.substring(i, i + 2));
            }
        }
        buf.setLength(0);
    }

    private static boolean isAsciiWord(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9');
    }

    /** CJK 判定（汉字/假名/谚文，按 Unicode 文字系统）；中日韩标点属 Common 不算 CJK */
    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /* ---------------- 索引行解析（包可见供测试） ---------------- */

    /**
     * 行 → 索引行：解析 metadata JSONB 取 source/title；解析失败容错降级
     * （docName 退回 chunkId {@code #} 前段、title 置空，不影响索引）；id 空缺或
     * content 空白的行返回 null（由构建方过滤）。
     */
    static Chunk toChunk(String id, String content, String metadataJson) {
        if (id == null || id.isBlank() || content == null || content.isBlank()) {
            return null;
        }
        int hash = id.indexOf('#');
        String docName = hash > 0 ? id.substring(0, hash) : id;
        String title = "";
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                JsonNode meta = MAPPER.readTree(metadataJson);
                String source = meta.path("source").asText("");
                if (!source.isBlank()) {
                    docName = source;
                }
                title = meta.path("title").asText("");
            } catch (Exception e) {
                log.warn("[knowledge] chunk {} metadata 解析失败（docName 降级为 chunkId 前段）: {}",
                        id, e.toString());
            }
        }
        return new Chunk(id, docName, title, content, tokenize(content));
    }

    /** PG 行映射（SELECT id, content, metadata 列序与列名一一对应） */
    private static Chunk mapChunk(ResultSet rs, int rowNum) throws SQLException {
        return toChunk(rs.getString("id"), rs.getString("content"), rs.getString("metadata"));
    }

    /* ---------------- 索引构建（懒重建） ---------------- */

    /** 取索引：缓存命中直返；脏标记置位或该 kb 组合未建索引时，在 monitor 内重建 */
    private KbIndex indexFor(String cacheKey, List<String> kbs) {
        KbIndex cached = indexByKbKey.get(cacheKey);
        if (!stale && cached != null) {
            return cached;
        }
        synchronized (buildMonitor) {
            cached = indexByKbKey.get(cacheKey);
            if (!stale && cached != null) {
                return cached;
            }
            if (stale) {
                // 脏重建：sync/delete 已改动底层数据，全部旧快照作废，只重建本次请求涉及的 kb 组合
                indexByKbKey = Map.of();
            }
            KbIndex built = buildIndex(kbs);
            if (built != null) {
                Map<String, KbIndex> next = new HashMap<>(indexByKbKey);
                next.put(cacheKey, built);
                indexByKbKey = Map.copyOf(next);
            }
            stale = false;
            return built;
        }
    }

    /**
     * 拉取指定 kb 组合的 chunk 并建索引：kbs 空去掉 WHERE 全量拉，否则按
     * {@code metadata->>'kb' = ANY (?)} 过滤（String[] 参数）；护栏触发返回 null
     * （不建索引，且保持可重试——下次检索重查总数，护栏恢复后自动可用）。
     */
    private KbIndex buildIndex(List<String> kbs) {
        if (maxChunks > 0) {
            long total = Objects.requireNonNullElse(
                    vectorJdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName, Long.class), 0L);
            if (total > maxChunks) {
                if (!guardrailWarned) {
                    log.warn("[knowledge] BM25 索引跳过：chunk 总数 {} 超过护栏 bm25-max-chunks={}（不建索引，检索退化为纯向量）",
                            total, maxChunks);
                    guardrailWarned = true;
                }
                return null;
            }
            guardrailWarned = false;
        }
        String sql = kbs.isEmpty()
                ? "SELECT id, content, metadata FROM " + tableName
                : "SELECT id, content, metadata FROM " + tableName + " WHERE metadata->>'kb' = ANY (?)";
        // kbs 非空时单参数为 String[]（装进 Object[] 传给可变参数位，JDBC 侧作 text 数组绑定）
        List<Chunk> rows = kbs.isEmpty()
                ? vectorJdbcTemplate.query(sql, KnowledgeBm25Index::mapChunk)
                : vectorJdbcTemplate.query(sql, KnowledgeBm25Index::mapChunk,
                        new Object[]{kbs.toArray(String[]::new)});
        List<Chunk> chunks = rows.stream().filter(Objects::nonNull).toList();
        if (chunks.isEmpty()) {
            log.info("[knowledge] BM25 索引无数据（kb={}），检索该库将返回空", kbs.isEmpty() ? "全部" : String.join(",", kbs));
        }
        return new KbIndex(chunks);
    }

    /** kbs 归一化：去 null/空白、去重、排序（保证缓存键稳定，不同书写顺序同键） */
    private static List<String> normalizeKbs(List<String> kbs) {
        if (kbs == null || kbs.isEmpty()) {
            return List.of();
        }
        return kbs.stream()
                .map(kb -> kb == null ? "" : kb.trim())
                .filter(kb -> !kb.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /** 缓存键：归一化 kb 列表逗号拼接；空表 = 全库 */
    private static String cacheKey(List<String> normalizedKbs) {
        return String.join(",", normalizedKbs);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    /** BM25 命中：chunk id + 出处 + 得分 + 片段原文（供混合融合与调试检索全文展示） */
    public record Bm25Hit(String chunkId, String docName, String title, double score, String text) {
    }

    /** 索引行：一行 chunk 的解析结果（含分词），包可见仅供测试构造 */
    record Chunk(String chunkId, String docName, String title, String text, List<String> terms) {
    }

    /**
     * 单个 kb 组合的倒排索引快照（构建后只读）：postings 维护 词元 → {chunkId → tf}，
     * df 直接取 postings 桶大小，文档长度取自 Chunk 分词数。构建在 monitor 内完成后
     * 经 volatile 快照发布，检索线程只读无锁。
     */
    private static final class KbIndex {

        private final Map<String, Chunk> chunksById;
        private final Map<String, Map<String, Integer>> postings;
        private final int docCount;
        private final double avgDocLength;

        KbIndex(List<Chunk> chunks) {
            Map<String, Chunk> byId = new HashMap<>();
            Map<String, Map<String, Integer>> postingMap = new HashMap<>();
            long totalLen = 0;
            for (Chunk chunk : chunks) {
                byId.put(chunk.chunkId(), chunk);
                totalLen += chunk.terms().size();
                for (String term : chunk.terms()) {
                    postingMap.computeIfAbsent(term, k -> new HashMap<>()).merge(chunk.chunkId(), 1, Integer::sum);
                }
            }
            this.chunksById = byId;
            this.postings = postingMap;
            this.docCount = chunks.size();
            this.avgDocLength = chunks.isEmpty() ? 1.0 : (double) totalLen / chunks.size();
        }

        /** BM25 评分：score = Σ_queryTerm idf × tf×(k1+1) / (tf + k1×(1-b+b×len/avg)) */
        List<Bm25Hit> search(List<String> queryTerms, int limit) {
            Map<String, Double> scores = new HashMap<>();
            for (String term : queryTerms) {
                Map<String, Integer> posting = postings.get(term);
                if (posting == null || posting.isEmpty()) {
                    continue;
                }
                double df = posting.size();
                double idf = Math.log(1.0 + (docCount - df + 0.5) / (df + 0.5));
                for (Map.Entry<String, Integer> entry : posting.entrySet()) {
                    double tf = entry.getValue();
                    double lenNorm = K1 * (1 - B + B * chunksById.get(entry.getKey()).terms().size() / avgDocLength);
                    scores.merge(entry.getKey(), idf * tf * (K1 + 1) / (tf + lenNorm), Double::sum);
                }
            }
            // 分数降序，同分按 chunkId 升序保证输出顺序确定
            Comparator<Map.Entry<String, Double>> byScoreDesc =
                    Map.Entry.<String, Double>comparingByValue().reversed()
                            .thenComparing(Map.Entry.<String, Double>comparingByKey());
            return scores.entrySet().stream()
                    .sorted(byScoreDesc)
                    .limit(limit > 0 ? limit : Integer.MAX_VALUE)
                    .map(entry -> {
                        Chunk chunk = chunksById.get(entry.getKey());
                        return new Bm25Hit(chunk.chunkId(), chunk.docName(), chunk.title(), entry.getValue(), chunk.text());
                    })
                    .toList();
        }
    }
}
