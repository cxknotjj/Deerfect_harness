package com.dark.javaHarness.knowledge;

import com.dark.javaHarness.config.knowledge.KnowledgeProperties;
import com.dark.javaHarness.view.PageResult;
import com.dark.javaHarness.domain.entity.KbDocumentEntity;
import com.dark.javaHarness.mapper.KbDocumentMapper;
import java.util.List;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * RAG 知识库服务门面（超长类拆分 2026-11-05）：只保留 {@link KnowledgeService} 对外契约与
 * 启动自动摄取入口，读写路径分别下沉为独立协作者：
 * <ul>
 *   <li>{@link KnowledgeIngestor}：增量摄取 / 孤儿清理 / 文档删除 / 台账分页（写路径）；</li>
 *   <li>{@link HybridKnowledgeRetriever}：向量 + BM25 + RRF 融合检索（读路径，不依赖写路径）。</li>
 * </ul>
 *
 * <p>chunk 口径（确定性 id {@code docName#idx} 与 kb/source 元数据）由写入侧统一产出，
 * 检索侧只消费向量库与索引，不再出现「同一份元数据两个写者」。
 *
 * <p>降级语义：VectorStore 经 {@link ObjectProvider} 惰性解析（启动零 PG 连接）；
 * sync 在向量库不可用时抛可读 {@link IllegalStateException}（管理端点直出），
 * search 降级返回空表——不影响应用启动与聊天主链路。
 */
public class KnowledgeServiceImpl implements KnowledgeService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServiceImpl.class);

    private final KnowledgeIngestor ingestor;
    private final HybridKnowledgeRetriever retriever;
    private final KnowledgeProperties props;

    public KnowledgeServiceImpl(ObjectProvider<VectorStore> storeProvider,
                                ObjectProvider<KnowledgeBm25Index> bm25Provider,
                                KnowledgeDocumentScanner scanner,
                                KbDocumentMapper mapper,
                                KnowledgeProperties props,
                                SqlSessionFactory sqlSessionFactory) {
        this.props = props;
        this.ingestor = new KnowledgeIngestor(storeProvider, bm25Provider, scanner, mapper, props, sqlSessionFactory);
        this.retriever = new HybridKnowledgeRetriever(storeProvider, bm25Provider, props);
    }

    @Override
    public KnowledgeSyncView sync() {
        return ingestor.sync();
    }

    @Override
    public boolean delete(String docName) {
        return ingestor.delete(docName);
    }

    @Override
    public PageResult<KbDocumentEntity> list(long page, long size) {
        return ingestor.list(page, size);
    }

    @Override
    public List<KnowledgeHit> search(String query, List<String> kbs) {
        return retriever.search(query, kbs);
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

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
