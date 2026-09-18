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

import com.dark.javaHarness.config.KnowledgeProperties;
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

        retriever.prefetch("lead", "s1", "如何部署", List.of("default"));
        String block = retriever.buildKnowledgeBlock("lead", "s1", "如何部署", List.of("default"));

        assertNotNull(block, "命中短路应返回预取知识块");
        assertTrue(block.contains("【出处1】"));
        verify(knowledgeService, times(1)).search("如何部署", List.of("default"));
    }

    @Test
    void prefetch_differentQuery_missesAndSearches() {
        when(knowledgeService.search(anyString(), anyList())).thenReturn(List.of(hit("a.md", 0.92)));

        retriever.prefetch("lead", "s1", "如何部署", List.of("default"));
        assertNotNull(retriever.buildKnowledgeBlock("lead", "s1", "子任务描述", List.of("default")));

        verify(knowledgeService, times(2)).search(anyString(), anyList());
    }

    @Test
    void prefetch_differentKbs_missesAndSearches() {
        when(knowledgeService.search(anyString(), anyList())).thenReturn(List.of(hit("a.md", 0.92)));

        retriever.prefetch("lead", "s1", "如何部署", List.of("default"));
        assertNotNull(retriever.buildKnowledgeBlock("lead", "s1", "如何部署", List.of("java")));

        verify(knowledgeService, times(2)).search(anyString(), anyList());
    }

    @Test
    void prefetch_noHits_notCached() {
        when(knowledgeService.search("无命中问题", List.of("default"))).thenReturn(List.of());

        retriever.prefetch("lead", "s1", "无命中问题", List.of("default"));
        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "无命中问题", List.of("default")));

        verify(knowledgeService, times(2)).search("无命中问题", List.of("default"));
    }

    @Test
    void prefetchCache_overflow_clearsAll() {
        when(knowledgeService.search(anyString(), anyList())).thenReturn(List.of(hit("a.md", 0.92)));

        for (int i = 0; i < 513; i++) {
            retriever.prefetch("lead", "sid-" + i, "如何部署", List.of("default"));
        }
        assertNotNull(retriever.buildKnowledgeBlock("lead", "sid-0", "如何部署", List.of("default")));

        // 513 次预取各查一次；容量超限整体清空后 sid-0 未命中，组装时再现查 1 次
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
}
