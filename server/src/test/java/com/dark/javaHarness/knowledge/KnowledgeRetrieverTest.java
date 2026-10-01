package com.dark.javaHarness.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.knowledge.KnowledgeProperties;
import com.dark.javaHarness.domain.dto.KnowledgeSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * KnowledgeRetriever 单测：未绑定知识库跳过（null/空 kbs）、角色策略跳过、查询长度下限、
 * 命中渲染与出处记录、预算截断、agent 知识库绑定（kbs 透传与解析）。
 *
 * <p>绑定语义：agent 表 knowledge 列 NULL/空白 = 未绑定，不触发检索；非空 = 仅检索绑定库。
 * 未绑定场景断言 {@code verifyNoInteractions}，防止误改回「检索全部」的旧行为。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRetrieverTest {

    @Mock
    private KnowledgeService knowledgeService;

    private KnowledgeProperties props;
    private KnowledgeRetriever retriever;

    @BeforeEach
    void setUp() {
        props = new KnowledgeProperties();
        props.setTopK(4);
        props.setMinScore(0.5);
        props.setContextBudget(3000);
        props.setMinQueryChars(0);
        retriever = new KnowledgeRetriever(knowledgeService, props);
    }

    private KnowledgeService.KnowledgeHit hit(String doc, double score) {
        return new KnowledgeService.KnowledgeHit(doc, "标题-" + doc, score, "片段内容（" + doc + "）");
    }

    @Test
    void nullKbs_unbound_skipsSearch() {
        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "这是一个足够长的问题", null));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void emptyKbs_skipsSearch() {
        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "这是一个足够长的问题", List.of()));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void aggregatorRole_skippedWithoutSearch() {
        assertNull(retriever.buildKnowledgeBlock("aggregator", "s1", "这是一个足够长的问题", List.of("default")));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void blankUser_skipped() {
        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "   ", List.of("default")));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void shortQuery_belowMinChars_skipped() {
        props.setMinQueryChars(10);
        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "短问题", List.of("default")));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void noHits_returnsNull() {
        when(knowledgeService.search("这个问题需要知识吗", List.of("default"))).thenReturn(List.of());
        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "这个问题需要知识吗", List.of("default")));
    }

    @Test
    void hit_rendersCitationsAndRecordsSources() {
        when(knowledgeService.search("如何部署", List.of("default"))).thenReturn(List.of(hit("a.md", 0.92)));

        String block = retriever.buildKnowledgeBlock("lead", "s1", "如何部署", List.of("default"));

        assertNotNull(block);
        assertTrue(block.contains("【知识库检索结果】"));
        assertTrue(block.contains("【出处1】"));
        assertTrue(block.contains("《标题-a.md》"), "出处头应含文档标题");
        assertTrue(block.contains("a.md"), "出处头应含文件名");
        assertTrue(block.contains("片段内容（a.md）"));
        assertTrue(block.contains("禁止编造出处"), "应携带引用指令");

        List<KnowledgeSource> sources = retriever.recentSources("s1");
        assertEquals(1, sources.size());
        assertEquals(new KnowledgeSource("a.md", "标题-a.md", 0.92), sources.get(0));
    }

    @Test
    void boundKbs_passedThroughToSearch() {
        List<String> kbs = List.of("java", "frontend");
        when(knowledgeService.search("如何部署", kbs)).thenReturn(List.of(hit("a.md", 0.92)));

        assertNotNull(retriever.buildKnowledgeBlock("lead", "s1", "如何部署", kbs));
    }

    @Test
    void budgetExhausted_dropsLowScoreHits() {
        // 低分命中正文 1 万中文字符（≈1 万 token，远超 3000 预算）→ 累加到该条时 break 丢弃
        KnowledgeService.KnowledgeHit huge =
                new KnowledgeService.KnowledgeHit("big-b.md", "标题-big-b.md", 0.6, "长".repeat(10_000));
        when(knowledgeService.search("预算截断问题", List.of("default"))).thenReturn(List.of(hit("a.md", 0.9), huge));

        String block = retriever.buildKnowledgeBlock("lead", "s1", "预算截断问题", List.of("default"));

        assertNotNull(block);
        assertTrue(block.contains("a.md"), "高分命中应保留");
        assertFalse(block.contains("big-b.md"), "超预算的低分命中应被丢弃");
        // 出处记录与渲染一致（只记被引用的）
        assertEquals(1, retriever.recentSources("s1").size());
    }

    @Test
    void budgetZero_meansUnlimited() {
        props.setContextBudget(0);
        when(knowledgeService.search("不设预算", List.of("default"))).thenReturn(
                List.of(hit("a.md", 0.9), hit("b.md", 0.8)));

        String block = retriever.buildKnowledgeBlock("lead", "s1", "不设预算", List.of("default"));

        assertNotNull(block);
        assertTrue(block.contains("【出处2】"), "context-budget 0 = 不截断，全部命中注入");
    }

    @Test
    void budgetSmallerThanHeader_returnsNull() {
        props.setContextBudget(1); // 头尾固定开销即超限 → 一条都放不下
        when(knowledgeService.search("放不下", List.of("default"))).thenReturn(List.of(hit("a.md", 0.9)));

        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "放不下", List.of("default")),
                "连一条都放不下时应返回 null（不注入空块）");
        assertTrue(retriever.recentSources("s1").isEmpty());
    }

    @Test
    void recentSources_unknownSession_returnsEmpty() {
        assertTrue(retriever.recentSources("no-such-session").isEmpty());
        assertTrue(retriever.recentSources(null).isEmpty());
    }

    @Test
    void hit_withoutSessionId_notRecorded() {
        when(knowledgeService.search("无会话场景", List.of("default"))).thenReturn(List.of(hit("a.md", 0.9)));

        assertNotNull(retriever.buildKnowledgeBlock("lead", null, "无会话场景", List.of("default")));
        assertTrue(retriever.recentSources(null).isEmpty());
    }

    // ===== 检索限时超时包裹（app.knowledge.search-timeout-seconds）=====

    @Test
    void timeoutEnabled_normalSearch_rendersAsBefore() {
        props.setSearchTimeoutSeconds(5);
        when(knowledgeService.search("如何部署", List.of("default"))).thenReturn(List.of(hit("a.md", 0.92)));

        String block = retriever.buildKnowledgeBlock("lead", "s1", "如何部署", List.of("default"));

        assertNotNull(block, "限时内正常完成应与治理前渲染一致");
        assertTrue(block.contains("【出处1】"));
    }

    @Test
    void timeoutEnabled_searchHangs_degradesNullWithinLimit() {
        props.setSearchTimeoutSeconds(1);
        when(knowledgeService.search("挂起问题", List.of("default"))).thenAnswer(inv -> {
            Thread.sleep(5000); // 模拟嵌入端点挂起
            return List.of(hit("a.md", 0.9));
        });

        long start = System.currentTimeMillis();
        String block = retriever.buildKnowledgeBlock("lead", "s1", "挂起问题", List.of("default"));

        assertNull(block, "超时应降级 null（不注入知识块不阻断主链路）");
        assertTrue(System.currentTimeMillis() - start < 4000, "应秒级降级而非等满 5s");
    }

    @Test
    void timeoutZero_passThrough_propagatesException() {
        props.setSearchTimeoutSeconds(0);
        when(knowledgeService.search("直通异常", List.of("default"))).thenThrow(new RuntimeException("嵌入挂了"));

        assertThrows(RuntimeException.class,
                () -> retriever.buildKnowledgeBlock("lead", "s1", "直通异常", List.of("default")),
                "0 = 关闭包裹直通现状，异常语义与治理前一致（向上传播不吞）");
    }

    @Test
    void timeoutEnabled_searchThrows_degradesNull() {
        props.setSearchTimeoutSeconds(5);
        when(knowledgeService.search("异常问题", List.of("default"))).thenThrow(new RuntimeException("PG 不可达"));

        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "异常问题", List.of("default")),
                "限时模式检索异常应静默降级 null");
    }

    // ===== 预取缓存与命中短路（prefetch + buildKnowledgeBlock 短路）=====

    @Test
    void prefetch_sameQueryAndKbs_shortCircuitsSearch() {
        when(knowledgeService.search("如何部署", List.of("default"))).thenReturn(List.of(hit("a.md", 0.92)));

        retriever.prefetch("lead", "s1", "如何部署", List.of("default"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        String block = retriever.buildKnowledgeBlock("lead", "s1", "如何部署", List.of("default"));

        assertNotNull(block, "命中短路应返回预取知识块");
        assertTrue(block.contains("【出处1】"));
        verify(knowledgeService, times(1)).search("如何部署", List.of("default"));
    }

    @Test
    void prefetch_sameSessionMultipleQueries_coexistAndHit() {
        when(knowledgeService.search(anyString(), anyList())).thenReturn(List.of(hit("a.md", 0.92)));

        // 入口预取（query=用户原话）+ 两个子任务预取（query=各自子任务文本），同会话三条目共存
        retriever.prefetch("lead", "s1", "用户原话", List.of("default"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        retriever.prefetch("researcher", "s1", "子任务一描述", List.of("default"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        retriever.prefetch("coder", "s1", "子任务二描述", List.of("default"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);

        // 三个条目各自命中，互不覆盖
        assertNotNull(retriever.buildKnowledgeBlock("lead", "s1", "用户原话", List.of("default")));
        assertNotNull(retriever.buildKnowledgeBlock("researcher", "s1", "子任务一描述", List.of("default")));
        assertNotNull(retriever.buildKnowledgeBlock("coder", "s1", "子任务二描述", List.of("default")));

        // 底层 search 只被调 prefetch 内部次数（3 次），组装时对同一 query 不重复现查
        verify(knowledgeService, times(3)).search(anyString(), anyList());
    }

    @Test
    void prefetch_sameSessionSameQueryDifferentKbs_missesAndSearches() {
        when(knowledgeService.search(anyString(), anyList())).thenReturn(List.of(hit("a.md", 0.92)));

        // 同会话同 query 两次预取不同 kb 绑定（复合键相同，后写覆盖前写）
        retriever.prefetch("lead", "s1", "如何部署", List.of("default"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        retriever.prefetch("lead", "s1", "如何部署", List.of("java"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);

        // kbs=default 条目已被覆盖 → 不命中现查；kbs=java 条目命中
        assertNotNull(retriever.buildKnowledgeBlock("lead", "s1", "如何部署", List.of("default")));
        assertNotNull(retriever.buildKnowledgeBlock("lead", "s1", "如何部署", List.of("java")));

        // 预取 2 次 + build(default) 现查 1 次 = 3 次；build(java) 命中不再查
        verify(knowledgeService, times(3)).search(anyString(), anyList());
    }

    @Test
    void prefetch_differentQuery_missesAndSearches() {
        when(knowledgeService.search(anyString(), anyList())).thenReturn(List.of(hit("a.md", 0.92)));

        retriever.prefetch("lead", "s1", "如何部署", List.of("default"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        assertNotNull(retriever.buildKnowledgeBlock("lead", "s1", "子任务描述", List.of("default")));

        verify(knowledgeService, times(2)).search(anyString(), anyList());
    }

    @Test
    void prefetch_differentKbs_missesAndSearches() {
        when(knowledgeService.search(anyString(), anyList())).thenReturn(List.of(hit("a.md", 0.92)));

        retriever.prefetch("lead", "s1", "如何部署", List.of("default"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        assertNotNull(retriever.buildKnowledgeBlock("lead", "s1", "如何部署", List.of("java")));

        verify(knowledgeService, times(2)).search(anyString(), anyList());
    }

    @Test
    void prefetch_noHits_notCached() {
        when(knowledgeService.search("无命中问题", List.of("default"))).thenReturn(List.of());

        retriever.prefetch("lead", "s1", "无命中问题", List.of("default"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "无命中问题", List.of("default")));

        verify(knowledgeService, times(2)).search("无命中问题", List.of("default"));
    }

    @Test
    void prefetchCache_overflow_clearsAll() {
        when(knowledgeService.search(anyString(), anyList())).thenReturn(List.of(hit("a.md", 0.92)));

        // 条目口径：同会话 513 个不同 query = 513 个条目（复合键 sid|query），超限整体清空
        for (int i = 0; i < 513; i++) {
            retriever.prefetch("lead", "s1", "问题-" + i, List.of("default"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        }
        // 清空后条目「问题-0」已丢失 → 组装时未命中再现查
        assertNotNull(retriever.buildKnowledgeBlock("lead", "s1", "问题-0", List.of("default")));

        // 513 次预取各查一次；容量超限整体清空后「问题-0」未命中，组装时再现查 1 次
        verify(knowledgeService, times(514)).search(anyString(), anyList());
    }

    @Test
    void parseBinding_blank_returnsNull() {
        assertNull(KnowledgeRetriever.parseBinding(null));
        assertNull(KnowledgeRetriever.parseBinding(""));
        assertNull(KnowledgeRetriever.parseBinding("  , ,"));
    }

    @Test
    void parseBinding_csv_trimsAndDedups() {
        assertEquals(List.of("java", "frontend"),
                KnowledgeRetriever.parseBinding("java, frontend ,java"));
        assertEquals(List.of("a b"), KnowledgeRetriever.parseBinding(" a b "));
        // 表达式清洗（单引号剔除）统一由 kbFilterExpression 收口，此处不再处理
        assertEquals(List.of("'java'"), KnowledgeRetriever.parseBinding("'java'"));
    }

    // ===== 检索观测采集（kb_retrieval_log，recorder 非 null 时四类事件）=====

    @Mock
    private com.dark.javaHarness.service.impl.observe.KbRetrievalRecorder recorder;

    private KnowledgeRetriever retrieverWithRecorder() {
        return new KnowledgeRetriever(knowledgeService, props, recorder);
    }

    /** 现查命中：落 inline_query 行（hit_count=注入条数、ok=1、轨迹归因透传） */
    @Test
    void recorder_inlineQuery_capturesRow() {
        when(knowledgeService.search("如何部署", List.of("default"))).thenReturn(List.of(hit("a.md", 0.92)));

        assertNotNull(retrieverWithRecorder().buildKnowledgeBlock("lead", "s1", "如何部署",
                List.of("default"), KnowledgeRetriever.SOURCE_INLINE, "turn-1", "trace-1"));

        org.mockito.ArgumentCaptor<com.dark.javaHarness.domain.KbRetrievalLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.dark.javaHarness.domain.KbRetrievalLog.class);
        verify(recorder).record(captor.capture());
        com.dark.javaHarness.domain.KbRetrievalLog row = captor.getValue();
        assertEquals("inline_query", row.source());
        assertEquals("s1", row.sessionId());
        assertEquals("lead", row.agentName());
        assertEquals(1, row.hitCount());
        assertEquals(Boolean.TRUE, row.ok());
        assertEquals("turn-1", row.turnId());
        assertEquals("trace-1", row.traceId());
        assertNotNull(row.startedAt());
        assertNotNull(row.durationMs());
    }

    /** 无命中：落 inline_query 行且 hit_count=0（ok=1） */
    @Test
    void recorder_noHit_capturesZeroCount() {
        when(knowledgeService.search("这个问题需要知识吗", List.of("default"))).thenReturn(List.of());

        assertNull(retrieverWithRecorder().buildKnowledgeBlock("lead", "s1", "这个问题需要知识吗",
                List.of("default"), KnowledgeRetriever.SOURCE_INLINE, null, null));

        org.mockito.ArgumentCaptor<com.dark.javaHarness.domain.KbRetrievalLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.dark.javaHarness.domain.KbRetrievalLog.class);
        verify(recorder).record(captor.capture());
        assertEquals(0, captor.getValue().hitCount());
        assertEquals(Boolean.TRUE, captor.getValue().ok());
    }

    /** 检索异常降级：落 ok=0 行且 error_msg 非空（行为不变，仍返回 null） */
    @Test
    void recorder_searchFailure_capturesDegradation() {
        props.setSearchTimeoutSeconds(1);
        when(knowledgeService.search(anyString(), anyList()))
                .thenThrow(new RuntimeException("pg down"));

        assertNull(retrieverWithRecorder().buildKnowledgeBlock("lead", "s1", "如何部署",
                List.of("default"), KnowledgeRetriever.SOURCE_INLINE, null, null));

        org.mockito.ArgumentCaptor<com.dark.javaHarness.domain.KbRetrievalLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.dark.javaHarness.domain.KbRetrievalLog.class);
        verify(recorder).record(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().ok());
        assertTrue(captor.getValue().errorMsg() != null && !captor.getValue().errorMsg().isBlank(),
                "降级行应携带 error_msg");
    }

    /** 预取后组装命中短路：第一行 source=预取标记，第二行 source=cache_hit（hit_count=NULL） */
    @Test
    void recorder_prefetchThenCacheHit_capturesBothSources() {
        when(knowledgeService.search("如何部署", List.of("default"))).thenReturn(List.of(hit("a.md", 0.92)));
        KnowledgeRetriever r = retrieverWithRecorder();

        r.prefetch("lead", "s1", "如何部署", List.of("default"), KnowledgeRetriever.SOURCE_ENTRY_PREFETCH);
        assertNotNull(r.buildKnowledgeBlock("lead", "s1", "如何部署", List.of("default")));

        org.mockito.ArgumentCaptor<com.dark.javaHarness.domain.KbRetrievalLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.dark.javaHarness.domain.KbRetrievalLog.class);
        verify(recorder, times(2)).record(captor.capture());
        com.dark.javaHarness.domain.KbRetrievalLog first = captor.getAllValues().get(0);
        com.dark.javaHarness.domain.KbRetrievalLog second = captor.getAllValues().get(1);
        assertEquals(KnowledgeRetriever.SOURCE_ENTRY_PREFETCH, first.source());
        assertEquals(KnowledgeRetriever.SOURCE_CACHE_HIT, second.source());
        assertNull(second.hitCount(), "cache_hit 行 hit_count 应为 NULL");
    }

    /** 零行为短路（未绑定知识库等）不记行 */
    @Test
    void recorder_zeroBehaviorShortCircuit_noRows() {
        assertNull(retrieverWithRecorder().buildKnowledgeBlock("lead", "s1", "这是一个足够长的问题", null));
        verifyNoInteractions(recorder);
    }
}
