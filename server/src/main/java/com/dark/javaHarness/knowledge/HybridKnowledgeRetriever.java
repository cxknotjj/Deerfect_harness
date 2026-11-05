package com.dark.javaHarness.knowledge;

import com.dark.javaHarness.config.knowledge.KnowledgeProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 知识读路径（自 {@link KnowledgeServiceImpl} 拆出，超长类拆分 2026-11-05）：
 * 纯向量检索 + BM25 关键词 leg + RRF 倒数排名融合，与写入路径完全解耦
 * （只读 VectorStore / BM25 索引，不触碰台账与嵌入写入）。
 *
 * <p>降级语义：向量库不可用时返回空表（不影响聊天主链路）；BM25 leg 内部自降级，
 * 效果等价纯向量排序——融合是增强不是义务。
 */
final class HybridKnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeRetriever.class);

    /** RRF 融合常数（业界常规 k=60，排名越靠前贡献越大、名次差异被 k 平滑） */
    static final int RRF_K = 60;

    /**
     * BM25 leg 候选窗倍数：融合需要两路都有足够候选，BM25 leg 取比 top-k 更宽的窗口
     * （max(topK×4, 20)；topK=0 不限），最终输出仍受 top-k 总条数约束
     */
    static final int BM25_CANDIDATE_FACTOR = 4;

    private final ObjectProvider<VectorStore> storeProvider;

    /**
     * BM25 内存索引（hybrid-enabled=true 时才有 bean）：经 ObjectProvider 注入，
     * 关闭时 getIfAvailable 返回 null 走纯向量路径；getIfAvailable 可能抛
     * BeanCreationException（PG 未就绪），由 search 外层 catch 统一降级空表
     */
    private final ObjectProvider<KnowledgeBm25Index> bm25Provider;
    private final KnowledgeProperties props;

    HybridKnowledgeRetriever(ObjectProvider<VectorStore> storeProvider,
                             ObjectProvider<KnowledgeBm25Index> bm25Provider,
                             KnowledgeProperties props) {
        this.storeProvider = storeProvider;
        this.bm25Provider = bm25Provider;
        this.props = props;
    }

    /** 检索（空白 query 直接空表；任何异常降级空表） */
    List<KnowledgeService.KnowledgeHit> search(String query, List<String> kbs) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            // 混合检索分发：hybrid-enabled 且 BM25 索引 bean 可用 → 双路 RRF 融合；
            // 关闭/未装配走纯向量路径（逐字节现状逻辑，不查 PG content、不建索引）
            if (props.isHybridEnabled()) {
                KnowledgeBm25Index bm25 = bm25Provider.getIfAvailable();
                if (bm25 != null) {
                    return searchHybrid(query, kbs, bm25);
                }
            }
            VectorStore store = resolveStore();
            if (store == null) {
                return List.of();
            }
            return store.similaritySearch(searchRequest(query, kbs)).stream()
                    .map(this::toHit)
                    .toList();
        } catch (Exception e) {
            // 调试检索降级空表：PG 不可用不影响主链路（与 KnowledgeRetriever 同口径）；
            // 含 bm25Provider.getIfAvailable 的 BeanCreationException（懒创建 PG 未就绪）
            log.warn("[knowledge] 检索失败（向量库不可用？）: {}", rootMessage(e));
            return List.of();
        }
    }

    /**
     * 混合检索：向量 leg（现状逻辑，min-score/top-k 照旧作用于本路）+ BM25 leg
     * （关键词/编号/专有名词字面精确匹配，不受 min-score 约束——两路分数语义不同
     * 不可混用阈值）→ RRF（k=60）倒数排名融合 → 分数归一化 (0,1] 输出。
     *
     * <p>降级口径：向量 leg 抛异常走外层 catch 降级空表；BM25 leg 内部自降级空表
     * （护栏触发/PG 抖动），效果等价纯向量排序，融合是增强不是义务。
     */
    private List<KnowledgeService.KnowledgeHit> searchHybrid(String query, List<String> kbs, KnowledgeBm25Index bm25) {
        VectorStore store = resolveStore();
        if (store == null) {
            return List.of();
        }
        List<Document> vectorDocs = store.similaritySearch(searchRequest(query, kbs));
        // BM25 leg 候选窗：比 top-k 宽（融合需要候选余量），最终输出条数仍受 top-k 约束
        int bm25Limit = props.getTopK() > 0
                ? Math.max(props.getTopK() * BM25_CANDIDATE_FACTOR, 20)
                : 0;
        List<KnowledgeBm25Index.Bm25Hit> bm25Hits = bm25.search(query, kbs, bm25Limit);
        return rrfFuse(vectorDocs, bm25Hits);
    }

    /**
     * RRF 融合：score = Σ_leg 1/(k+rank)（每路 rank 从 1 起），按 chunk id 聚合
     * （两路都命中的 chunk 得分叠加置顶）；融合分除以理论最大值 2/(k+1) 归一化到
     * (0,1]（单路 rank1 = 0.5，双路 rank1 = 1.0），保持「相关度 %.2f」渲染契约。
     * 输出条数受 top-k 约束（top-k=0 不限）；同分按 docName 升序保证顺序确定。
     * 融合分地板（fusion-min-score，0 = 不启用）：低于地板的命中视为噪音丢弃——
     * 纯 BM25 单路弱匹配（中文 bigram 虚高）稳定落在 ~0.48，真命中实测 ≥0.94。
     */
    List<KnowledgeService.KnowledgeHit> rrfFuse(List<Document> vectorDocs,
                                                List<KnowledgeBm25Index.Bm25Hit> bm25Hits) {
        record Fused(String docName, String title, String text, double rrf) {
        }
        Map<String, Fused> fused = new HashMap<>();
        int rank = 1;
        for (Document doc : vectorDocs) {
            // id 缺失（病态场景）给合成 key：仍可输出，只是不与 BM25 聚合
            String key = doc.getId() != null && !doc.getId().isBlank() ? doc.getId() : "vec@" + rank;
            double contribution = 1.0 / (RRF_K + rank);
            Fused f = fused.get(key);
            if (f == null) {
                fused.put(key, new Fused(
                        String.valueOf(doc.getMetadata().getOrDefault("source", "")),
                        String.valueOf(doc.getMetadata().getOrDefault("title", "")),
                        doc.getText(),
                        contribution));
            } else {
                fused.put(key, new Fused(f.docName(), f.title(), f.text(), f.rrf() + contribution));
            }
            rank++;
        }
        rank = 1;
        for (KnowledgeBm25Index.Bm25Hit hit : bm25Hits) {
            double contribution = 1.0 / (RRF_K + rank);
            Fused f = fused.get(hit.chunkId());
            if (f == null) {
                fused.put(hit.chunkId(), new Fused(hit.docName(), hit.title(), hit.text(), contribution));
            } else {
                // 两路命中同 chunk：文本取向量 leg 的（同 id 同内容，语义等价），分数叠加
                fused.put(hit.chunkId(), new Fused(f.docName(), f.title(), f.text(), f.rrf() + contribution));
            }
            rank++;
        }
        // 归一化基准 = 双路 rank1 叠加的理论最大 2/(k+1)；rankWeightSum 兜底空表场景
        double maxScore = 2.0 / (RRF_K + 1);
        double floor = props.getFusionMinScore();
        return fused.values().stream()
                .sorted(java.util.Comparator.<Fused>comparingDouble(Fused::rrf).reversed()
                        .thenComparing(Fused::docName))
                .limit(props.getTopK() > 0 ? props.getTopK() : Long.MAX_VALUE)
                .map(f -> new KnowledgeService.KnowledgeHit(f.docName(), f.title(),
                        Math.min(1.0, f.rrf() / maxScore), f.text()))
                .filter(hit -> floor <= 0 || hit.score() >= floor)
                .toList();
    }

    /** 向量库惰性解析（检索路径）：未装配返回 null，调用方降级空表 */
    private VectorStore resolveStore() {
        return storeProvider.getIfAvailable();
    }

    /** 向量命中 → KnowledgeHit（metadata source/title 提取口径统一） */
    private KnowledgeService.KnowledgeHit toHit(Document doc) {
        return new KnowledgeService.KnowledgeHit(
                String.valueOf(doc.getMetadata().getOrDefault("source", "")),
                String.valueOf(doc.getMetadata().getOrDefault("title", "")),
                doc.getScore() == null ? 0 : doc.getScore(),
                doc.getText());
    }

    /** 相似度检索请求（top-k 0 = 不限条数；min-score 直接映射相似度阈值；kbs 非空时按 metadata.kb 过滤） */
    SearchRequest searchRequest(String query, List<String> kbs) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query);
        if (props.getTopK() > 0) {
            builder.topK(props.getTopK());
        }
        builder.similarityThreshold(props.getMinScore());
        String kbFilter = kbFilterExpression(kbs);
        if (kbFilter != null) {
            builder.filterExpression(kbFilter);
        }
        return builder.build();
    }

    /**
     * kb 过滤表达式（如 {@code kb in ['java','frontend']}）；kbs 空/全空白返回 null（不过滤）。
     * kb 取自目录名，单引号属病态输入直接剔除（filter 表达式字符串字面量分隔符）。
     */
    static String kbFilterExpression(List<String> kbs) {
        if (kbs == null || kbs.isEmpty()) {
            return null;
        }
        String tokens = kbs.stream()
                .map(kb -> kb == null ? "" : kb.replace("'", "").trim())
                .filter(kb -> !kb.isEmpty())
                .distinct()
                .map(kb -> "'" + kb + "'")
                .collect(Collectors.joining(","));
        return tokens.isEmpty() ? null : "kb in [" + tokens + "]";
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
