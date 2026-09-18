package com.dark.javaHarness.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.config.KnowledgeProperties;
import com.dark.javaHarness.domain.dto.PageResult;
import com.dark.javaHarness.domain.entity.KbDocumentEntity;
import com.dark.javaHarness.mapper.KbDocumentMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * RAG 知识库服务实现：增量摄取 / 删除 / 分页 / 调试检索。
 *
 * <p>增量判据：kb_document 行的 (doc_name, mtime, kb) 与文件现状比对——mtime 与 kb
 * 均未变跳过（kb 变更即重摄取：存量行 kb 为 NULL 时借下次 sync 自愈补齐 chunk
 * metadata.kb），
 * 变更文档先删旧 chunk（确定性 id = {@code docName#idx}，重摄取可精确覆盖）再重新
 * 切分嵌入入库，最后 upsert 摄取记录。
 *
 * <p>降级语义：VectorStore 经 {@link ObjectProvider} 惰性解析（启动零 PG 连接）；
 * sync 在向量库不可用时抛可读 {@link IllegalStateException}（管理端点直出），
 * search 降级返回空表——对齐 SandboxToolProvider「不影响主链路」先例。
 */
public class KnowledgeServiceImpl implements KnowledgeService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServiceImpl.class);

    /** 单 chunk 字符上限（中文 1 字符 ≈ 1 token，~700 字符兼顾检索粒度与注入预算） */
    static final int CHUNK_CHARS = 700;

    /** 相邻 chunk 重叠字符数（语义边界缓冲） */
    static final int OVERLAP_CHARS = 100;

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
    private final KnowledgeDocumentScanner scanner;
    private final KbDocumentMapper mapper;
    private final KnowledgeProperties props;

    /**
     * sync 并发防护锁：手动 sync 与（后续）目录监听触发的自动 sync 可能并发到达，
     * 需串行化保证同一时刻至多一个增量摄取在跑（向量库不出现并发写、不重复摄取）；
     * tryLock 语义：后到者快速失败——立即抛可读异常，不阻塞等待排队
     * （监听触发场景由静默期后的下一轮事件自然补齐，手动 API 场景可感知报错）。
     */
    private final ReentrantLock syncLock = new ReentrantLock();

    public KnowledgeServiceImpl(ObjectProvider<VectorStore> storeProvider,
                                ObjectProvider<KnowledgeBm25Index> bm25Provider,
                                KnowledgeDocumentScanner scanner,
                                KbDocumentMapper mapper,
                                KnowledgeProperties props) {
        this.storeProvider = storeProvider;
        this.bm25Provider = bm25Provider;
        this.scanner = scanner;
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public KnowledgeSyncView sync() {
        // 并发防护：手动 sync 与监听触发同时到达时，后到者 tryLock 快速失败、不阻塞等待
        if (!syncLock.tryLock()) {
            throw new IllegalStateException("已有知识库同步在执行，请稍后重试");
        }
        try {
            VectorStore store = requireStore();
            List<KnowledgeDocumentScanner.KbFile> files = scanner.scan();
            int updated = 0;
            int skipped = 0;
            int chunks = 0;
            for (KnowledgeDocumentScanner.KbFile file : files) {
                KbDocumentEntity row = rowOf(file.name());
                if (row != null && row.getStatus() != null && row.getStatus() == 1
                        && Objects.equals(row.getMtime(), file.mtime())
                        && Objects.equals(row.getKb(), file.kb())) {
                    skipped++;
                    continue;
                }
                int oldChunkCount = row == null || row.getChunkCount() == null ? 0 : row.getChunkCount();
                List<String> pieces = MarkdownChunker.chunk(file.text(), CHUNK_CHARS, OVERLAP_CHARS);
                // 先删旧向量再写入（同 id 覆盖语义由删除保证；chunk 数减少时不留孤儿行）
                deleteChunks(store, file.name(), oldChunkCount);
                try {
                    List<Document> docs = new ArrayList<>(pieces.size());
                    for (int i = 0; i < pieces.size(); i++) {
                        docs.add(new Document(chunkId(file.name(), i), pieces.get(i), Map.of(
                                "source", file.name(),
                                "title", file.title(),
                                "chunkIndex", i,
                                "kb", file.kb())));
                    }
                    if (!docs.isEmpty()) {
                        addInBatches(store, docs);
                    }
                } catch (Exception e) {
                    // 写入失败兜底：旧 chunk 已删、新 chunk 未写全——台账行置 0（失败待重试），
                    // 下次 sync 不再被「status=1 + mtime 未变」增量判据跳过，强制重摄取补齐向量；
                    // 新文件（无台账行）天然重扫，无需置位
                    if (row != null) {
                        row.setStatus(0);
                        mapper.updateById(row);
                    }
                    throw new IllegalStateException("向量库写入失败（" + file.name() + "）: " + rootMessage(e), e);
                }
                upsertRow(row, file, pieces.size());
                updated++;
                chunks += pieces.size();
                log.info("[knowledge] 已摄取 '{}'：{} chunk（旧 {} chunk 已删）", file.name(), pieces.size(), oldChunkCount);
            }
            cleanupOrphans(store, files);
            return new KnowledgeSyncView(files.size(), updated, skipped, chunks);
        } finally {
            syncLock.unlock();
            // BM25 索引失效（成功/失败路径都失效）：成功=数据已变；失败=旧 chunk 可能已删、
            // 内容与索引不一致——宁可重建也不让混合检索读到陈旧索引（懒重建不阻塞摄取路径）
            invalidateBm25();
        }
    }

    /**
     * 孤儿清理：台账有、本轮扫描结果中没有的行 = 文档已从磁盘删除——删除其向量
     * chunk 与台账行，避免绑定 agent 继续检索到已删内容。
     *
     * <p>安全阀：扫描结果为空时跳过清理（目录缺失/整体不可读时 scanner 返回空表，
     * 此时全量清理会误删整个库）。
     */
    private void cleanupOrphans(VectorStore store, List<KnowledgeDocumentScanner.KbFile> files) {
        if (files.isEmpty()) {
            return;
        }
        Set<String> scannedNames = files.stream()
                .map(KnowledgeDocumentScanner.KbFile::name)
                .collect(Collectors.toSet());
        List<KbDocumentEntity> rows = mapper.selectList(new QueryWrapper<KbDocumentEntity>()
                .select("id", "doc_name", "chunk_count"));
        for (KbDocumentEntity row : rows) {
            if (scannedNames.contains(row.getDocName())) {
                continue;
            }
            deleteChunks(store, row.getDocName(), row.getChunkCount() == null ? 0 : row.getChunkCount());
            mapper.deleteById(row.getId());
            log.info("[knowledge] 已清理孤儿文档 '{}'（磁盘已删除，台账 {} chunk）",
                    row.getDocName(), row.getChunkCount());
        }
    }

    @Override
    public boolean delete(String docName) {
        if (docName == null || docName.isBlank()) {
            return false;
        }
        KbDocumentEntity row = rowOf(docName.trim());
        if (row == null) {
            return false;
        }
        VectorStore store = requireStore();
        deleteChunks(store, row.getDocName(), row.getChunkCount() == null ? 0 : row.getChunkCount());
        mapper.deleteById(row.getId());
        log.info("[knowledge] 已删除 '{}'（{} chunk）", row.getDocName(), row.getChunkCount());
        invalidateBm25();
        return true;
    }

    @Override
    public PageResult<KbDocumentEntity> list(long page, long size) {
        Page<KbDocumentEntity> result = mapper.selectPage(
                Page.of(Math.max(1, page), Math.min(Math.max(1, size), 200)),
                new QueryWrapper<KbDocumentEntity>().orderByAsc("doc_name"));
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<KnowledgeHit> search(String query, List<String> kbs) {
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
    private List<KnowledgeHit> searchHybrid(String query, List<String> kbs, KnowledgeBm25Index bm25) {
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
     */
    List<KnowledgeHit> rrfFuse(List<Document> vectorDocs, List<KnowledgeBm25Index.Bm25Hit> bm25Hits) {
        record Fused(String docName, String title, String text, double rrf) {
        }
        Map<String, Fused> fused = new java.util.HashMap<>();
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
        return fused.values().stream()
                .sorted(java.util.Comparator.<Fused>comparingDouble(Fused::rrf).reversed()
                        .thenComparing(Fused::docName))
                .limit(props.getTopK() > 0 ? props.getTopK() : Long.MAX_VALUE)
                .map(f -> new KnowledgeHit(f.docName(), f.title(),
                        Math.min(1.0, f.rrf() / maxScore), f.text()))
                .toList();
    }

    /** 向量库惰性解析（检索路径）：未装配返回 null，调用方降级空表 */
    private VectorStore resolveStore() {
        return storeProvider.getIfAvailable();
    }

    /** 向量命中 → KnowledgeHit（metadata source/title 提取口径统一） */
    private KnowledgeHit toHit(Document doc) {
        return new KnowledgeHit(
                String.valueOf(doc.getMetadata().getOrDefault("source", "")),
                String.valueOf(doc.getMetadata().getOrDefault("title", "")),
                doc.getScore() == null ? 0 : doc.getScore(),
                doc.getText());
    }

    /**
     * BM25 索引失效标记（sync/delete 后调用，best-effort）：仅置脏标记不重建，
     * 重建发生在下次混合检索前；hybrid 关闭或 bean 未装配时为无操作。
     */
    private void invalidateBm25() {
        if (!props.isHybridEnabled()) {
            return;
        }
        try {
            KnowledgeBm25Index bm25 = bm25Provider.getIfAvailable();
            if (bm25 != null) {
                bm25.invalidate();
            }
        } catch (Exception e) {
            // 失效标记失败（懒创建失败等）不影响摄取主流程：下次检索前重建仍会拉到新数据
            log.debug("[knowledge] BM25 索引失效标记失败（忽略）: {}", rootMessage(e));
        }
    }

    /** 启动自动增量摄取（app.knowledge.auto-sync-on-startup，默认关）：失败仅告警不影响启动 */
    @Override
    public void run(ApplicationArguments args) {
        if (!props.isAutoSyncOnStartup()) {
            return;
        }
        try {
            KnowledgeSyncView view = sync();
            log.info("[knowledge] 启动自动摄取完成：扫描 {} 更新 {} 跳过 {}", view.scanned(), view.updated(), view.skipped());
        } catch (Exception e) {
            log.warn("[knowledge] 启动自动摄取失败（不影响启动）: {}", rootMessage(e));
        }
    }

    /* ---------------- 内部 ---------------- */

    /** 向量库惰性解析：未装配（依赖缺失/初始化失败）抛可读异常 */
    private VectorStore requireStore() {
        VectorStore store = storeProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("知识库向量库未装配（检查 app.knowledge.* 配置与 pgvector 依赖）");
        }
        return store;
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

    private void deleteChunks(VectorStore store, String docName, int oldChunkCount) {
        if (oldChunkCount <= 0) {
            return;
        }
        List<String> ids = new ArrayList<>(oldChunkCount);
        for (int i = 0; i < oldChunkCount; i++) {
            ids.add(chunkId(docName, i));
        }
        try {
            store.delete(ids);
        } catch (Exception e) {
            throw new IllegalStateException("向量库旧 chunk 删除失败（" + docName + "）: " + rootMessage(e), e);
        }
    }

    /**
     * 分批写入向量库（app.knowledge.embed-batch-size，0 = 不分批一次提交）：
     * DashScope 兼容模式单请求硬上限 10 条文本，默认 8 留余量——Spring AI 默认
     * BatchingStrategy 只按总 token 打包不管条数，大文档切出大量 chunk 时不分批
     * 会整批 400 失败。
     */
    private void addInBatches(VectorStore store, List<Document> docs) {
        int batchSize = props.getEmbedBatchSize();
        if (batchSize <= 0 || docs.size() <= batchSize) {
            store.add(docs);
            return;
        }
        for (int from = 0; from < docs.size(); from += batchSize) {
            store.add(docs.subList(from, Math.min(from + batchSize, docs.size())));
        }
    }

    /** 确定性 chunk id：重摄取时旧向量可精确删除（PgVectorStore TEXT 主键） */
    static String chunkId(String docName, int index) {
        return docName + "#" + index;
    }

    private KbDocumentEntity rowOf(String docName) {
        return mapper.selectOne(new QueryWrapper<KbDocumentEntity>().eq("doc_name", docName));
    }

    private void upsertRow(KbDocumentEntity existing, KnowledgeDocumentScanner.KbFile file, int chunkCount) {
        KbDocumentEntity row = existing != null ? existing : new KbDocumentEntity();
        row.setDocName(file.name());
        row.setKb(file.kb());
        row.setTitle(file.title());
        row.setMtime(file.mtime());
        row.setChunkCount(chunkCount);
        row.setStatus(1);
        if (existing == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
