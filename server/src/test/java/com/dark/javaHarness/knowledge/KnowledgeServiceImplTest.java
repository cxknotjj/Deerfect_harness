package com.dark.javaHarness.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.KnowledgeProperties;
import com.dark.javaHarness.domain.entity.KbDocumentEntity;
import com.dark.javaHarness.mapper.KbDocumentMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * KnowledgeServiceImpl 单测（Mockito 边界 mock）：增量摄取比对（mtime + kb）、旧 chunk
 * 删除、分批嵌入（embed-batch-size）、写入失败台账置 0 待重试、向量库不可用的
 * 降级/报错语义、kb 过滤表达式与检索请求组装。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceImplTest {

    @Mock
    private ObjectProvider<VectorStore> storeProvider;
    @Mock
    private ObjectProvider<KnowledgeBm25Index> bm25Provider;
    @Mock
    private VectorStore store;
    @Mock
    private KnowledgeBm25Index bm25;
    @Mock
    private KnowledgeDocumentScanner scanner;
    @Mock
    private KbDocumentMapper mapper;

    private KnowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeServiceImpl(storeProvider, bm25Provider, scanner, mapper, new KnowledgeProperties());
    }

    private KnowledgeDocumentScanner.KbFile file(String name, String kb, long mtime, String text) {
        return new KnowledgeDocumentScanner.KbFile(name, kb, "标题-" + name, mtime, text);
    }

    private KbDocumentEntity row(String docName, String kb, long mtime, int chunkCount, int status) {
        KbDocumentEntity row = new KbDocumentEntity();
        row.setId(1L);
        row.setDocName(docName);
        row.setKb(kb);
        row.setMtime(mtime);
        row.setChunkCount(chunkCount);
        row.setStatus(status);
        return row;
    }

    @Test
    void sync_newFile_addsChunksAndInsertsRow() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 1, 0, 1), view);
        verify(store).add(anyList());
        verify(mapper).insert(any(KbDocumentEntity.class));
    }

    @Test
    void sync_unchangedMtimeAndKb_skippedWithoutEmbedding() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(row("a.md", "default", 100L, 1, 1));

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 0, 1, 0), view);
        verify(store, never()).add(anyList());
        verify(mapper, never()).insert(any(KbDocumentEntity.class));
        verify(mapper, never()).updateById(any(KbDocumentEntity.class));
    }

    @Test
    void sync_legacyRowWithoutKb_reingestedForMetadataHealing() {
        // V13 迁移后存量行 kb=NULL（chunk 无 kb 元数据）：kb 不一致 → 重摄取自愈补齐
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(row("a.md", null, 100L, 1, 1));

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 1, 0, 1), view);
        verify(store).delete(List.of("a.md#0"));
        verify(store).add(anyList());
        verify(mapper).updateById(any(KbDocumentEntity.class));
    }

    @Test
    void sync_changedMtime_deletesOldChunksThenReEmbeds() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 200L, "新正文")));
        when(mapper.selectOne(any())).thenReturn(row("a.md", "default", 100L, 2, 1));

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 1, 0, 1), view);
        verify(store).delete(List.of("a.md#0", "a.md#1"));
        verify(store).add(anyList());
        verify(mapper).updateById(any(KbDocumentEntity.class));
    }

    @Test
    void sync_subdirFile_writesKbMetadataAndLedgesIt() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("java/spring.md", "java", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<KbDocumentEntity> rowCaptor = ArgumentCaptor.forClass(KbDocumentEntity.class);

        service.sync();

        verify(store).add(docsCaptor.capture());
        assertEquals("java", docsCaptor.getValue().get(0).getMetadata().get("kb"));
        assertEquals("java/spring.md#0", docsCaptor.getValue().get(0).getId());
        verify(mapper).insert(rowCaptor.capture());
        assertEquals("java", rowCaptor.getValue().getKb());
        assertEquals("java/spring.md", rowCaptor.getValue().getDocName());
    }

    @Test
    void sync_storeUnavailable_throwsReadableError() {
        // requireStore 在扫描之前执行：store 未装配时直接抛，scanner 不会被触达
        when(storeProvider.getIfAvailable()).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::sync);
        assertTrue(ex.getMessage().contains("向量库未装配"));
        verify(mapper, never()).insert(any(KbDocumentEntity.class));
    }

    @Test
    void sync_storeWriteFailure_wrapsAsIllegalState() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("pg down")).when(store).add(anyList());

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::sync);
        assertTrue(ex.getMessage().contains("向量库写入失败"));
        verify(mapper, never()).insert(any(KbDocumentEntity.class));
    }

    @Test
    void sync_largeDoc_batchesEmbedRequests() {
        // embed-batch-size=2：5 chunk → 3 次 add（2+2+1），规避 DashScope 单请求 10 条硬上限
        KnowledgeProperties props = new KnowledgeProperties();
        props.setEmbedBatchSize(2);
        KnowledgeServiceImpl batched = new KnowledgeServiceImpl(storeProvider, bm25Provider, scanner, mapper, props);
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "字".repeat(2400))));
        when(mapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);

        KnowledgeSyncView view = batched.sync();

        assertEquals(new KnowledgeSyncView(1, 1, 0, 5), view);
        verify(store, times(3)).add(docsCaptor.capture());
        List<List<Document>> batches = docsCaptor.getAllValues();
        assertEquals(List.of(2, 2, 1), batches.stream().map(List::size).toList(), "按上限分批，末批余量");
        assertEquals("a.md#4", batches.get(2).get(0).getId(), "批次按 chunk 序切分，确定性 id 不变");
    }

    @Test
    void sync_existingRowWriteFailure_marksRowFailedForRetry() {
        // 「先删后写」中断窗口兜底：旧 chunk 已删、写入失败 → 台账行置 0，
        // 下次 sync 不再被「status=1 + mtime 未变」判据跳过，强制重摄取补齐向量
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 200L, "新正文")));
        when(mapper.selectOne(any())).thenReturn(row("a.md", "default", 100L, 1, 1));
        org.mockito.Mockito.doThrow(new RuntimeException("pg down")).when(store).add(anyList());
        ArgumentCaptor<KbDocumentEntity> rowCaptor = ArgumentCaptor.forClass(KbDocumentEntity.class);

        assertThrows(IllegalStateException.class, () -> service.sync());

        verify(mapper).updateById(rowCaptor.capture());
        assertEquals(0, rowCaptor.getValue().getStatus(), "写入失败应置 0（失败待重试）");
        verify(mapper, never()).insert(any(KbDocumentEntity.class));
    }

    @Test
    void sync_ledgerRowMissingOnDisk_orphanCleanedUp() {
        // 孤儿清理：台账有、磁盘没有 → 删该文档 chunk + 台账行，绑定 agent 不再检索到已删内容
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(row("a.md", "default", 100L, 1, 1));
        when(mapper.selectList(any()))
                .thenReturn(List.of(row("ghost.md", "default", 50L, 2, 1)));

        service.sync();

        verify(store).delete(List.of("ghost.md#0", "ghost.md#1"));
        verify(mapper).deleteById(1L);
    }

    @Test
    void sync_emptyScan_skipsOrphanCleanup() {
        // 安全阀：目录缺失/整体不可读时 scanner 返回空表，跳过清理防全量误删
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of());

        service.sync();

        verify(mapper, never()).selectList(any());
        verify(store, never()).delete(anyList());
    }

    @Test
    void sync_concurrentCall_loserFailsFast_withoutDuplicateEmbedding() throws Exception {
        // 并发防护：两线程 CyclicBarrier 对齐同时 sync，持锁方阻塞在向量库写入处，
        // 后到者 tryLock 快速失败——恰好一个成功、一个抛可读异常，向量库只写一次
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);
        CountDownLatch enteredAdd = new CountDownLatch(1);
        CountDownLatch releaseAdd = new CountDownLatch(1);
        CountDownLatch loserAttempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredAdd.countDown();
            releaseAdd.await(10, TimeUnit.SECONDS);
            return null;
        }).when(store).add(anyList());

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<KnowledgeSyncView> syncTask = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
                return service.sync();
            } catch (IllegalStateException e) {
                loserAttempted.countDown();
                throw e;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<KnowledgeSyncView> first = pool.submit(syncTask);
            Future<KnowledgeSyncView> second = pool.submit(syncTask);

            // 持锁方已进入向量库写入、且后到者已 tryLock 快速失败后，才放行持锁方完成摄取
            //（保证后到者的 tryLock 尝试必然落在持锁窗口内，用例无时序竞态）
            assertTrue(enteredAdd.await(10, TimeUnit.SECONDS), "应有一个线程持锁进入向量库写入");
            assertTrue(loserAttempted.await(10, TimeUnit.SECONDS), "后到者应 tryLock 快速失败");
            releaseAdd.countDown();

            KnowledgeSyncView okView = null;
            List<Throwable> errors = new ArrayList<>();
            for (Future<KnowledgeSyncView> f : List.of(first, second)) {
                try {
                    okView = f.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
            assertNotNull(okView, "应恰好一个线程正常返回");
            assertEquals(new KnowledgeSyncView(1, 1, 0, 1), okView);
            assertEquals(1, errors.size(), "应恰好一个线程失败");
            IllegalStateException loser = assertInstanceOf(IllegalStateException.class, errors.get(0));
            assertEquals("已有知识库同步在执行，请稍后重试", loser.getMessage());
            verify(store, times(1)).add(anyList());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void delete_missingDoc_returnsFalse() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertFalse(service.delete("ghost.md"));
        verify(storeProvider, never()).getIfAvailable();
    }

    @Test
    void delete_existingDoc_removesChunksAndRow() {
        when(mapper.selectOne(any())).thenReturn(row("a.md", "default", 100L, 2, 1));
        when(storeProvider.getIfAvailable()).thenReturn(store);

        assertTrue(service.delete("a.md"));

        verify(store).delete(List.of("a.md#0", "a.md#1"));
        verify(mapper).deleteById(1L);
    }

    @Test
    void search_blankQuery_returnsEmptyWithoutStore() {
        assertTrue(service.search("  ", null).isEmpty());
    }

    @Test
    void search_storeUnavailable_degradesToEmpty() {
        when(storeProvider.getIfAvailable()).thenReturn(null);
        assertTrue(service.search("问题", null).isEmpty());
    }

    @Test
    void search_storeLazyInitFailure_degradesToEmpty() {
        // chat 路径首次检索会触发 vectorStore 懒创建：PG 未就绪时 getIfAvailable 抛
        // BeanCreationException——必须降级空表，不得把整个 chat 打成 FAILED（降级口径）
        when(storeProvider.getIfAvailable())
                .thenThrow(new BeanCreationException("vectorStore", "Failed to obtain JDBC Connection"));

        assertTrue(service.search("问题", null).isEmpty(), "懒创建失败应降级空表，不阻断主链路");
    }

    @Test
    void search_failure_degradesToEmpty() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("pg down"));

        assertTrue(service.search("问题", null).isEmpty(), "检索失败应降级空表，不阻断主链路");
    }

    @Test
    void search_hits_mappedToKnowledgeHit() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        Document doc = org.mockito.Mockito.mock(Document.class);
        when(doc.getMetadata()).thenReturn(Map.of("source", "a.md", "title", "标题-a.md"));
        when(doc.getText()).thenReturn("片段内容");
        when(doc.getScore()).thenReturn(0.87);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        List<KnowledgeService.KnowledgeHit> hits = service.search("问题", null);

        assertEquals(1, hits.size());
        assertEquals("a.md", hits.get(0).docName());
        assertEquals("标题-a.md", hits.get(0).title());
        assertEquals(0.87, hits.get(0).score());
        assertEquals("片段内容", hits.get(0).text());
    }

    @Test
    void search_boundKbs_requestCarriesFilterExpression() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ArgumentCaptor<SearchRequest> reqCaptor = ArgumentCaptor.forClass(SearchRequest.class);

        service.search("问题", List.of("java", "frontend"));

        verify(store).similaritySearch(reqCaptor.capture());
        assertNotNull(reqCaptor.getValue().getFilterExpression(), "绑定库非空时请求应携带 filter 表达式");
    }

    @Test
    void search_unbound_requestWithoutFilterExpression() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ArgumentCaptor<SearchRequest> reqCaptor = ArgumentCaptor.forClass(SearchRequest.class);

        service.search("问题", null);

        verify(store).similaritySearch(reqCaptor.capture());
        assertNull(reqCaptor.getValue().getFilterExpression(), "未绑定 = 不限，不应携带 filter");
    }

    /* ---------------- 混合检索（hybrid-enabled=true） ---------------- */

    /** 混合检索专用服务：hybrid 开启的独立 props（不影响其余用例的默认关闭语义） */
    private KnowledgeServiceImpl hybridService() {
        KnowledgeProperties props = new KnowledgeProperties();
        props.setHybridEnabled(true);
        return new KnowledgeServiceImpl(storeProvider, bm25Provider, scanner, mapper, props);
    }

    private Document vectorDoc(String id, String source, String text) {
        return new Document(id, text, Map.of("source", source, "title", "标题-" + source));
    }

    @Test
    void search_hybrid_disabledGoesVectorOnly_withoutTouchingBm25() {
        // 开关关闭零回归：不走 ObjectProvider 解析、不查 BM25，输出与纯向量路径一致
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                vectorDoc("a.md#0", "a.md", "向量片段")));

        List<KnowledgeService.KnowledgeHit> hits = service.search("问题", null);

        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).score(), "纯向量路径保留原始相似度分数语义");
        verifyNoInteractions(bm25Provider);
    }

    @Test
    void search_hybrid_dualLegHit_topsWithNormalizedScoreOne() {
        // 两路都命中同 chunk（rank1+rank1）：RRF 叠加后归一化到 1.0 置顶
        KnowledgeServiceImpl hybrid = hybridService();
        when(bm25Provider.getIfAvailable()).thenReturn(bm25);
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(vectorDoc("a.md#0", "a.md", "双路片段")));
        when(bm25.search(any(), any(), anyInt())).thenReturn(List.of(
                new KnowledgeBm25Index.Bm25Hit("a.md#0", "a.md", "标题-a.md", 9.0, "双路片段"),
                new KnowledgeBm25Index.Bm25Hit("b.md#0", "b.md", "标题-b.md", 3.0, "仅关键词命中")));

        List<KnowledgeService.KnowledgeHit> hits = hybrid.search("编号查询", null);

        assertEquals(2, hits.size(), "topK=0 = 不限条数");
        assertEquals("a.md", hits.get(0).docName());
        assertEquals(1.0, hits.get(0).score(), 1e-9, "双路 rank1 叠加 = 理论最大分，归一化到 1.0");
        assertEquals("b.md", hits.get(1).docName());
        assertEquals((KnowledgeServiceImpl.RRF_K + 1.0) / (2.0 * (KnowledgeServiceImpl.RRF_K + 2.0)), hits.get(1).score(), 1e-9,
                "单路 rank2 = (1/(k+2)) / (2/(k+1))，落在 (0,1]");
    }

    @Test
    void search_hybrid_bm25OnlyHit_entersResults() {
        // 关键词场景：向量 leg 无命中（低于阈值被过滤），BM25 leg 命中进入融合结果
        KnowledgeServiceImpl hybrid = hybridService();
        when(bm25Provider.getIfAvailable()).thenReturn(bm25);
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(bm25.search(any(), any(), anyInt())).thenReturn(List.of(
                new KnowledgeBm25Index.Bm25Hit("spec.md#2", "spec.md", "标题-spec.md", 5.0, "编号片段")));

        List<KnowledgeService.KnowledgeHit> hits = hybrid.search("ERR-4021 是什么", null);

        assertEquals(1, hits.size());
        assertEquals("spec.md", hits.get(0).docName());
        assertEquals(0.5, hits.get(0).score(), 1e-9, "单路 rank1 归一化 = 0.5");
    }

    @Test
    void search_hybrid_topKCapsFusedOutput() {
        // top-k 约束最终输出条数（两路候选再宽，输出仍按 top-k 截断）
        KnowledgeProperties props = new KnowledgeProperties();
        props.setHybridEnabled(true);
        props.setTopK(1);
        KnowledgeServiceImpl hybrid = new KnowledgeServiceImpl(storeProvider, bm25Provider, scanner, mapper, props);
        when(bm25Provider.getIfAvailable()).thenReturn(bm25);
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                vectorDoc("a.md#0", "a.md", "片段a")));
        when(bm25.search(any(), any(), anyInt())).thenReturn(List.of(
                new KnowledgeBm25Index.Bm25Hit("b.md#0", "b.md", "标题-b.md", 2.0, "片段b")));

        List<KnowledgeService.KnowledgeHit> hits = hybrid.search("问题", null);

        assertEquals(1, hits.size());
        assertEquals("a.md", hits.get(0).docName(), "双路 rank1 置顶且被 top-k 截留");
    }

    @Test
    void search_hybrid_bm25LegFailure_degradesToVectorOrdering() {
        // BM25 leg 内部降级空表（护栏/PG 抖动）：融合退化为纯向量排序，检索不失败
        KnowledgeServiceImpl hybrid = hybridService();
        when(bm25Provider.getIfAvailable()).thenReturn(bm25);
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                vectorDoc("a.md#0", "a.md", "片段")));
        when(bm25.search(any(), any(), anyInt())).thenReturn(List.of());

        List<KnowledgeService.KnowledgeHit> hits = hybrid.search("问题", null);

        assertEquals(1, hits.size());
        assertEquals("a.md", hits.get(0).docName());
    }

    /* ---------------- BM25 索引失效钩子 ---------------- */

    @Test
    void sync_hybridEnabled_invalidatesBm25Index() {
        KnowledgeServiceImpl hybrid = hybridService();
        when(bm25Provider.getIfAvailable()).thenReturn(bm25);
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);

        hybrid.sync();

        verify(bm25).invalidate();
    }

    @Test
    void sync_failure_stillInvalidatesBm25Index() {
        // 失败路径也失效：旧 chunk 可能已删、索引与库内容不一致——宁可重建不读陈旧索引
        KnowledgeServiceImpl hybrid = hybridService();
        when(bm25Provider.getIfAvailable()).thenReturn(bm25);
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("pg down")).when(store).add(anyList());

        assertThrows(IllegalStateException.class, hybrid::sync);

        verify(bm25).invalidate();
    }

    @Test
    void delete_hybridEnabled_invalidatesBm25Index() {
        KnowledgeServiceImpl hybrid = hybridService();
        when(bm25Provider.getIfAvailable()).thenReturn(bm25);
        when(mapper.selectOne(any())).thenReturn(row("a.md", "default", 100L, 2, 1));
        when(storeProvider.getIfAvailable()).thenReturn(store);

        hybrid.delete("a.md");

        verify(bm25).invalidate();
    }

    @Test
    void sync_hybridDisabled_neverTouchesBm25Provider() {
        // 关闭开关：摄取路径零 BM25 交互（不解析 bean、不标记失效）
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);

        service.sync();

        verifyNoInteractions(bm25Provider);
    }

    @Test
    void kbFilterExpression_nullWhenNoBinding() {
        assertNull(KnowledgeServiceImpl.kbFilterExpression(null));
        assertNull(KnowledgeServiceImpl.kbFilterExpression(List.of()));
        assertNull(KnowledgeServiceImpl.kbFilterExpression(List.of("  ", "")));
    }

    @Test
    void kbFilterExpression_inExpressionForBoundKbs() {
        assertEquals("kb in ['java','frontend']",
                KnowledgeServiceImpl.kbFilterExpression(List.of("java", "frontend")));
        assertEquals("kb in ['java']", KnowledgeServiceImpl.kbFilterExpression(List.of("'java'")),
                "单引号属病态输入应剔除");
    }

    @Test
    void chunkId_isDeterministic() {
        assertEquals("a.md#3", KnowledgeServiceImpl.chunkId("a.md", 3));
        assertEquals("java/spring.md#0", KnowledgeServiceImpl.chunkId("java/spring.md", 0),
                "子目录文档 name 带前缀，id 仍确定性");
    }
}
