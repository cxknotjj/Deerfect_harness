package com.dark.javaHarness.knowledge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * KnowledgeBm25Index 单测（Mockito mock JdbcTemplate，不连真实 PG）：分词器
 * （中英混合 / CJK bigram / v4 整体词元）、metadata 解析与容错降级、BM25 评分
 * 排序 sanity（含关键词文档靠前、tf 高者更靠前、score=0 不返回）、limit 截断、
 * kb 过滤的 SQL 形态与 ANY(?) 参数绑定、全库拉取不带 WHERE、缓存命中与 invalidate
 * 懒重建（重新触发 SQL 查询）、maxChunks 规模护栏（超限空表且不拉 content）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBm25IndexTest {

    private static final String TABLE = "vector_store_harness";

    @Mock
    private JdbcTemplate vectorJdbcTemplate;

    private KnowledgeBm25Index indexWithMax(int maxChunks) {
        return new KnowledgeBm25Index(vectorJdbcTemplate, TABLE, maxChunks);
    }

    /** 构造带完整 metadata 的索引行（source/title/kb 同摄取写入口径） */
    private KnowledgeBm25Index.Chunk chunk(String id, String kb, String content) {
        String docName = id.substring(0, id.indexOf('#'));
        String metadata = "{\"source\":\"" + docName + "\",\"title\":\"标题\",\"kb\":\"" + kb + "\"}";
        return KnowledgeBm25Index.toChunk(id, content, metadata);
    }

    /**
     * 桩定 content 全库拉取（kbs 为空时生产代码编译绑定 query(sql, rowMapper) 两参重载，
     * 与带 kb 参数的可变参数重载是不同 mock 签名，须分开桩定）。
     */
    private void stubFullPull(KnowledgeBm25Index.Chunk... rows) {
        when(vectorJdbcTemplate.query(anyString(),
                ArgumentMatchers.<RowMapper<KnowledgeBm25Index.Chunk>>any()))
                .thenReturn(List.of(rows));
    }

    /** 桩定按 kb 拉取（query(sql, rowMapper, 可变参数)，单个可变参数为装箱 String[]） */
    private void stubKbPull(List<KnowledgeBm25Index.Chunk> rows) {
        when(vectorJdbcTemplate.query(anyString(),
                ArgumentMatchers.<RowMapper<KnowledgeBm25Index.Chunk>>any(), any()))
                .thenReturn(rows);
    }

    @Test
    void tokenize_mixedChineseEnglishAlnum() {
        // 英文小写化；数字+字母混合段（v4）按整体词元；CJK 连续段切重叠 bigram
        assertEquals(List.of("使用", "spring", "框架", "v4", "版本"),
                KnowledgeBm25Index.tokenize("使用Spring框架v4版本"));
        assertEquals(List.of("知识", "识库"), KnowledgeBm25Index.tokenize("知识库"), "3 字 CJK 段切 2 个 bigram");
        assertEquals(List.of("知"), KnowledgeBm25Index.tokenize("知"), "段长为 1 输出单字");
        assertEquals(List.of("spring", "boot", "2024"), KnowledgeBm25Index.tokenize("Spring-Boot_2024"),
                "标点/下划线作分隔符，ASCII 连续段各自成词");
        assertEquals(List.of("a"), KnowledgeBm25Index.tokenize("A"));
    }

    @Test
    void tokenize_blankOrNull_returnsEmpty() {
        assertTrue(KnowledgeBm25Index.tokenize(null).isEmpty());
        assertTrue(KnowledgeBm25Index.tokenize("  \t ").isEmpty());
        assertTrue(KnowledgeBm25Index.tokenize("###---。。。").isEmpty(), "无词元字符（标点）返回空");
    }

    @Test
    void toChunk_parsesMetadataFromJson() {
        KnowledgeBm25Index.Chunk chunk = KnowledgeBm25Index.toChunk(
                "java/spring.md#0", "spring 正文",
                "{\"source\":\"java/spring.md\",\"title\":\"Spring 指南\",\"chunkIndex\":0,\"kb\":\"java\"}");
        assertEquals("java/spring.md#0", chunk.chunkId());
        assertEquals("java/spring.md", chunk.docName());
        assertEquals("Spring 指南", chunk.title());
        assertEquals(List.of("spring", "正文"), chunk.terms());
    }

    @Test
    void toChunk_brokenMetadata_fallsBackToChunkIdPrefix() {
        KnowledgeBm25Index.Chunk chunk = KnowledgeBm25Index.toChunk("java/spring.md#0", "spring 正文", "{not-json");
        assertEquals("java/spring.md", chunk.docName(), "metadata 解析失败时 docName 退回 chunkId # 前段");
        assertEquals("", chunk.title(), "解析失败 title 置空");
        assertEquals(List.of("spring", "正文"), chunk.terms(), "分词不受 metadata 解析失败影响");
    }

    @Test
    void search_keywordHits_rankAboveIrrelevant() {
        KnowledgeBm25Index index = indexWithMax(0);
        stubKbPull(List.of(
                chunk("kb/spring.md#0", "java", "spring boot 入门 spring 实战"),
                chunk("kb/misc.md#0", "java", "spring 微服务简介"),
                chunk("kb/queue.md#0", "java", "消息队列与流处理")));

        List<KnowledgeBm25Index.Bm25Hit> hits = index.search("spring", List.of("java"), 10);

        assertEquals(2, hits.size(), "不含关键词的文档 score=0 不应返回");
        assertEquals("kb/spring.md", hits.get(0).docName(), "词频更高的文档应排前");
        assertEquals("kb/misc.md", hits.get(1).docName());
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertTrue(hits.get(0).score() > 0);
        assertEquals("spring boot 入门 spring 实战", hits.get(0).text());
        assertEquals("标题", hits.get(0).title());
        assertEquals("kb/spring.md#0", hits.get(0).chunkId());
        // maxChunks=0（不限）不应触发总数护栏查询
        verify(vectorJdbcTemplate, never()).queryForObject(anyString(), eq(Long.class));
    }

    @Test
    void search_limitCapsResultCount() {
        KnowledgeBm25Index index = indexWithMax(0);
        stubFullPull(
                chunk("a.md#0", "default", "spring spring boot"),
                chunk("b.md#0", "default", "spring 文档一"),
                chunk("c.md#0", "default", "spring 文档二"));

        List<KnowledgeBm25Index.Bm25Hit> hits = index.search("spring", null, 2);

        assertEquals(2, hits.size(), "limit 应截断返回条数");
        assertEquals("a.md", hits.get(0).docName(), "词频最高者排前");
        assertEquals("b.md", hits.get(1).docName(), "同分文档按 chunkId 升序稳定输出");
    }

    @Test
    void search_blankQuery_returnsEmptyWithoutJdbc() {
        KnowledgeBm25Index index = indexWithMax(0);
        assertTrue(index.search(null, null, 10).isEmpty());
        assertTrue(index.search("   ", null, 10).isEmpty());
        assertTrue(index.search("###。。。", null, 10).isEmpty(), "分词后无词元的查询返回空");
        verifyNoInteractions(vectorJdbcTemplate);
    }

    @Test
    void search_kbFilter_passesKbArrayToSql() {
        KnowledgeBm25Index index = indexWithMax(0);
        AtomicReference<String> boundSql = new AtomicReference<>();
        AtomicReference<String[]> boundKbs = new AtomicReference<>();
        // 捕获 SQL 与 ANY(?) 绑定的 kb 数组（生产恒为单可变参数装箱 String[]）
        when(vectorJdbcTemplate.query(anyString(),
                ArgumentMatchers.<RowMapper<KnowledgeBm25Index.Chunk>>any(), any()))
                .thenAnswer(invocation -> {
                    boundSql.set(invocation.getArgument(0));
                    Object third = invocation.getArgument(2);
                    if (third instanceof String[] arr) {
                        boundKbs.set(arr);
                    } else if (third instanceof Object[] boxed && boxed.length > 0
                            && boxed[0] instanceof String[] arr) {
                        boundKbs.set(arr);
                    }
                    return List.of(chunk("py/doc.md#0", "python", "spring in python docs"));
                });

        List<KnowledgeBm25Index.Bm25Hit> hits = index.search("spring", List.of("python", "  "), 5);

        assertEquals(1, hits.size());
        assertEquals("py/doc.md", hits.get(0).docName());
        assertNotNull(boundSql.get());
        assertTrue(boundSql.get().contains("WHERE metadata->>'kb' = ANY (?)"),
                "绑定库非空时 SQL 应携带 kb 过滤");
        assertArrayEquals(new String[]{"python"}, boundKbs.get(), "空白 kb 应剔除后整体作为 ANY(?) 参数");
    }

    @Test
    void search_allKbs_pullsFullTableWithoutWhereFilter() {
        KnowledgeBm25Index index = indexWithMax(0);
        AtomicReference<String> boundSql = new AtomicReference<>();
        when(vectorJdbcTemplate.query(anyString(),
                ArgumentMatchers.<RowMapper<KnowledgeBm25Index.Chunk>>any()))
                .thenAnswer(invocation -> {
                    boundSql.set(invocation.getArgument(0));
                    return List.of(chunk("a.md#0", "default", "spring 文档"));
                });

        List<KnowledgeBm25Index.Bm25Hit> hits = index.search("spring", null, 10);

        assertEquals(1, hits.size(), "kbs 为空 = 不限，检索全部库");
        assertNotNull(boundSql.get());
        assertFalse(boundSql.get().contains("WHERE"), "kbs 空时应去掉 WHERE 全量拉取");
    }

    @Test
    void invalidate_nextSearchRebuildsAndRequeries() {
        KnowledgeBm25Index index = indexWithMax(0);
        stubFullPull(
                chunk("a.md#0", "default", "spring 旧文档"),
                chunk("a.md#1", "default", "spring 旧文档续"));

        assertEquals(2, index.search("spring", null, 10).size(), "首个索引含 2 个命中 chunk");
        assertEquals(2, index.search("spring", null, 10).size(), "未失效时走缓存");
        verify(vectorJdbcTemplate, times(1)).query(anyString(),
                ArgumentMatchers.<RowMapper<KnowledgeBm25Index.Chunk>>any());

        index.invalidate();
        // 重建后换新数据：第二次检索必须真的重新执行 SQL（否则仍是旧索引的 a.md）
        stubFullPull(chunk("b.md#0", "default", "spring 新文档"));

        List<KnowledgeBm25Index.Bm25Hit> hits = index.search("spring", null, 10);

        assertEquals(1, hits.size());
        assertEquals("b.md", hits.get(0).docName(), "invalidate 后应懒重建索引（重新拉取 PG）");
        verify(vectorJdbcTemplate, times(2)).query(anyString(),
                ArgumentMatchers.<RowMapper<KnowledgeBm25Index.Chunk>>any());
    }

    @Test
    void search_overGuardrail_skipsIndexWithoutContentQuery() {
        KnowledgeBm25Index index = indexWithMax(2);
        when(vectorJdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);

        assertTrue(index.search("spring", null, 10).isEmpty(), "chunk 总数超护栏应返回空表");

        verify(vectorJdbcTemplate).queryForObject(anyString(), eq(Long.class));
        verify(vectorJdbcTemplate, never()).query(anyString(),
                ArgumentMatchers.<RowMapper<KnowledgeBm25Index.Chunk>>any());
        verify(vectorJdbcTemplate, never()).query(anyString(),
                ArgumentMatchers.<RowMapper<KnowledgeBm25Index.Chunk>>any(), any());
    }
}
