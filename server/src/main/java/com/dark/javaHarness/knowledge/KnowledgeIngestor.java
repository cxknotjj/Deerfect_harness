package com.dark.javaHarness.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.config.knowledge.KnowledgeProperties;
import com.dark.javaHarness.domain.dto.PageResult;
import com.dark.javaHarness.domain.entity.KbDocumentEntity;
import com.dark.javaHarness.mapper.KbDocumentMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 知识写入路径（自 {@link KnowledgeServiceImpl} 拆出，超长类拆分 2026-11-05）：
 * 增量摄取（扫描/切分/嵌入/台账）与孤儿清理、文档删除、台账分页。
 *
 * <p>增量判据：kb_document 行的 (doc_name, mtime, kb) 与文件现状比对——mtime 与 kb
 * 均未变跳过（kb 变更即重摄取：存量行 kb 为 NULL 时借下次 sync 自愈补齐 chunk
 * metadata.kb）；变更文档先删旧 chunk（确定性 id = {@code docName#idx}）再重新
 * 切分嵌入入库，最后 upsert 摄取记录。
 *
 * <p>降级语义：VectorStore 经 {@link ObjectProvider} 惰性解析；sync 在向量库不可用时
 * 抛可读 {@link IllegalStateException}（管理端点直出）——写路径不静默。
 */
final class KnowledgeIngestor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestor.class);

    /** 单 chunk 字符上限（中文 1 字符 ≈ 1 token，~700 字符兼顾检索粒度与注入预算） */
    static final int CHUNK_CHARS = 700;

    /** 相邻 chunk 重叠字符数（语义边界缓冲） */
    static final int OVERLAP_CHARS = 100;

    private final ObjectProvider<VectorStore> storeProvider;

    /**
     * BM25 内存索引（hybrid-enabled=true 时才有 bean）：写入后仅置脏标记（懒重建），
     * getIfAvailable 可能抛 BeanCreationException（PG 未就绪），失效标记失败静默。
     */
    private final ObjectProvider<KnowledgeBm25Index> bm25Provider;
    private final KnowledgeDocumentScanner scanner;
    private final KbDocumentMapper mapper;
    private final KnowledgeProperties props;
    /** 批量写库会话工厂；null = 单测场景，退回逐条写 */
    private final SqlSessionFactory sqlSessionFactory;

    /**
     * sync 并发防护锁：手动 sync 与目录监听触发的自动 sync 可能并发到达，
     * 需串行化保证同一时刻至多一个增量摄取在跑（向量库不出现并发写、不重复摄取）；
     * tryLock 语义：后到者快速失败——立即抛可读异常，不阻塞等待排队。
     */
    private final ReentrantLock syncLock = new ReentrantLock();

    KnowledgeIngestor(ObjectProvider<VectorStore> storeProvider,
                      ObjectProvider<KnowledgeBm25Index> bm25Provider,
                      KnowledgeDocumentScanner scanner,
                      KbDocumentMapper mapper,
                      KnowledgeProperties props,
                      SqlSessionFactory sqlSessionFactory) {
        this.storeProvider = storeProvider;
        this.bm25Provider = bm25Provider;
        this.scanner = scanner;
        this.mapper = mapper;
        this.props = props;
        this.sqlSessionFactory = sqlSessionFactory;
    }

    /** 增量摄取（并发防护 + 孤儿清理 + 索引失效标记） */
    KnowledgeSyncView sync() {
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
            List<KbDocumentEntity> pendingRows = new ArrayList<>();
            // 台账全量载入一次：增量判据逐文档比对，循环内逐条查会退化成 N+1（优化审查 2026-11-03），
            // 同一份快照也供本轮孤儿清理复用（本轮新增行都在扫描结果内，不会是孤儿）
            List<KbDocumentEntity> ledger = files.isEmpty() ? List.of() : mapper.selectList(null);
            Map<String, KbDocumentEntity> ledgerByName = indexByName(ledger);
            try {
                for (KnowledgeDocumentScanner.KbFile file : files) {
                    KbDocumentEntity row = ledgerByName.get(file.name());
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
                    pendingRows.add(upsertRow(row, file, pieces.size()));
                    updated++;
                    chunks += pieces.size();
                    log.info("[knowledge] 已摄取 '{}'：{} chunk（旧 {} chunk 已删）", file.name(), pieces.size(), oldChunkCount);
                }
            } catch (RuntimeException e) {
                // 失败前已摄取成功的文件仍需落台账（尽力而为），避免下一轮把已完成文档重复嵌入
                try {
                    flushRows(pendingRows);
                } catch (RuntimeException flushError) {
                    e.addSuppressed(flushError);
                }
                throw e;
            }
            flushRows(pendingRows);
            cleanupOrphans(store, files, ledger);
            return new KnowledgeSyncView(files.size(), updated, skipped, chunks);
        } finally {
            syncLock.unlock();
            // BM25 索引失效（成功/失败路径都失效）：成功=数据已变；失败=旧 chunk 可能已删、
            // 内容与索引不一致——宁可重建也不让混合检索读到陈旧索引（懒重建不阻塞摄取路径）
            invalidateBm25();
        }
    }

    /** 删除文档：先删向量库全部 chunk，再删摄取记录；文档不存在返回 false */
    boolean delete(String docName) {
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

    /** 摄取记录分页（doc_name 升序，单页上限 200） */
    PageResult<KbDocumentEntity> list(long page, long size) {
        Page<KbDocumentEntity> result = mapper.selectPage(
                Page.of(Math.max(1, page), Math.min(Math.max(1, size), 200)),
                new QueryWrapper<KbDocumentEntity>().orderByAsc("doc_name"));
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 孤儿清理：台账有、本轮扫描结果中没有的行 = 文档已从磁盘删除——删除其向量
     * chunk 与台账行，避免绑定 agent 继续检索到已删内容。
     *
     * <p>安全阀：扫描结果为空时跳过清理（目录缺失/整体不可读时 scanner 返回空表，
     * 此时全量清理会误删整个库）。
     *
     * @param ledger 本轮开始时载入的台账快照（与增量判据同一份，省掉一次全表查询）
     */
    private void cleanupOrphans(VectorStore store, List<KnowledgeDocumentScanner.KbFile> files,
                                List<KbDocumentEntity> ledger) {
        if (files.isEmpty()) {
            return;
        }
        Set<String> scannedNames = files.stream()
                .map(KnowledgeDocumentScanner.KbFile::name)
                .collect(Collectors.toSet());
        List<Long> orphanIds = new ArrayList<>();
        for (KbDocumentEntity row : ledger) {
            if (scannedNames.contains(row.getDocName())) {
                continue;
            }
            deleteChunks(store, row.getDocName(), row.getChunkCount() == null ? 0 : row.getChunkCount());
            orphanIds.add(row.getId());
            log.info("[knowledge] 已清理孤儿文档 '{}'（磁盘已删除，台账 {} chunk）",
                    row.getDocName(), row.getChunkCount());
        }
        if (!orphanIds.isEmpty()) {
            // 批量删台账行（原先逐条 deleteById）
            mapper.deleteByIds(orphanIds);
        }
    }

    /** 向量库惰性解析：未装配（依赖缺失/初始化失败）抛可读异常 */
    private VectorStore requireStore() {
        VectorStore store = storeProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("知识库向量库未装配（检查 app.knowledge.* 配置与 pgvector 依赖）");
        }
        return store;
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

    /** 台账按 doc_name 建索引：增量判据逐文档比对，用一次全量查询代替循环内逐条查（N+1） */
    private static Map<String, KbDocumentEntity> indexByName(List<KbDocumentEntity> rows) {
        Map<String, KbDocumentEntity> index = new HashMap<>(rows.size() * 2);
        for (KbDocumentEntity row : rows) {
            if (row.getDocName() != null) {
                index.put(row.getDocName(), row);
            }
        }
        return index;
    }

    private KbDocumentEntity rowOf(String docName) {
        return mapper.selectOne(new QueryWrapper<KbDocumentEntity>().eq("doc_name", docName));
    }

    private KbDocumentEntity upsertRow(KbDocumentEntity existing, KnowledgeDocumentScanner.KbFile file, int chunkCount) {
        KbDocumentEntity row = existing != null ? existing : new KbDocumentEntity();
        row.setDocName(file.name());
        row.setKb(file.kb());
        row.setTitle(file.title());
        row.setMtime(file.mtime());
        row.setChunkCount(chunkCount);
        row.setStatus(1);
        return row;
    }

    /**
     * 批量落库台账行：SqlSession BATCH 模式下逐条登记、一次 flush/commit，减少往返；
     * 无 SqlSessionFactory（单测）时退回逐条写，行为不变。
     */
    private void flushRows(List<KbDocumentEntity> rows) {
        if (rows.isEmpty()) {
            return;
        }
        if (sqlSessionFactory == null) {
            for (KbDocumentEntity row : rows) {
                if (row.getId() == null) {
                    mapper.insert(row);
                } else {
                    mapper.updateById(row);
                }
            }
            return;
        }
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            KbDocumentMapper batchMapper = session.getMapper(KbDocumentMapper.class);
            for (KbDocumentEntity row : rows) {
                if (row.getId() == null) {
                    batchMapper.insert(row);
                } else {
                    batchMapper.updateById(row);
                }
            }
            session.flushStatements();
            session.commit();
        }
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

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
