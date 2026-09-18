package com.dark.javaHarness.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.knowledge.KnowledgeDocumentScanner;
import com.dark.javaHarness.knowledge.KnowledgeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

/**
 * KnowledgeAdminController 单测：/search 的 full 参数（默认三字段向后兼容 / true 带片段原文）、
 * /upload 的合法落盘（根目录/kb 子目录/重名覆盖）与非法输入拒绝（扩展名/大小/kb 白名单/
 * 路径穿越/隐藏文件）、知识库未启用 503 语义。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeAdminControllerTest {

    @TempDir
    Path tempDir;

    @Mock
    private ObjectProvider<KnowledgeService> knowledgeServiceProvider;
    @Mock
    private KnowledgeService knowledgeService;

    private KnowledgeAdminController controller;

    @BeforeEach
    void setUp() {
        // 真实 Scanner 指向临时目录：上传落盘路径断言不依赖 mock
        controller = new KnowledgeAdminController(knowledgeServiceProvider,
                new KnowledgeDocumentScanner(tempDir.toString()));
    }

    private void enableService() {
        when(knowledgeServiceProvider.getIfAvailable()).thenReturn(knowledgeService);
    }

    private KnowledgeService.KnowledgeHit hit(String docName, String text) {
        return new KnowledgeService.KnowledgeHit(docName, "标题-" + docName, 0.87, text);
    }

    /* ---------------- /search full 参数 ---------------- */

    @Test
    void search_fullFalse_keepsBackwardCompatibleSourceShape() {
        enableService();
        when(knowledgeService.search("问题", null)).thenReturn(List.of(hit("a.md", "片段")));

        List<?> result = controller.search("问题", null, false);

        assertEquals(1, result.size());
        assertInstanceOf(com.dark.javaHarness.domain.dto.KnowledgeSource.class, result.get(0),
                "full=false 应保持 KnowledgeSource 三字段现状（向后兼容）");
        assertEquals("a.md", ((com.dark.javaHarness.domain.dto.KnowledgeSource) result.get(0)).docName());
    }

    @Test
    void search_fullTrue_returnsHitsWithChunkText() {
        enableService();
        when(knowledgeService.search("问题", null)).thenReturn(List.of(hit("a.md", "片段原文")));

        List<?> result = controller.search("问题", null, true);

        assertEquals(1, result.size());
        KnowledgeService.KnowledgeHit full = assertInstanceOf(KnowledgeService.KnowledgeHit.class, result.get(0));
        assertEquals("片段原文", full.text(), "full=true 应额外返回片段原文");
    }

    @Test
    void search_serviceDisabled_throws503() {
        when(knowledgeServiceProvider.getIfAvailable()).thenReturn(null);

        assertThrows(KnowledgeAdminController.KnowledgeDisabledException.class,
                () -> controller.search("问题", null, false));
    }

    /* ---------------- /upload 合法上传 ---------------- */

    @Test
    void upload_validMarkdown_withKb_writesToSubDirectory() throws Exception {
        enableService();
        MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/markdown",
                "# 笔记\n正文".getBytes());

        String docName = controller.upload(file, "java");

        assertEquals("java/notes.md", docName);
        assertEquals("# 笔记\n正文",
                Files.readString(tempDir.resolve("java").resolve("notes.md")), "落盘内容一致");
    }

    @Test
    void upload_validTxt_withoutKb_writesToRoot() throws Exception {
        enableService();
        MockMultipartFile file = new MockMultipartFile("file", "readme.txt", "text/plain",
                "内容".getBytes());

        String docName = controller.upload(file, null);

        assertEquals("readme.txt", docName);
        assertEquals("内容", Files.readString(tempDir.resolve("readme.txt")));
    }

    @Test
    void upload_sameName_overwritesExisting() throws Exception {
        enableService();
        controller.upload(new MockMultipartFile("file", "a.md", "text/markdown", "旧内容".getBytes()), null);

        controller.upload(new MockMultipartFile("file", "a.md", "text/markdown", "新内容".getBytes()), null);

        assertEquals("新内容", Files.readString(tempDir.resolve("a.md")), "重名覆盖写（mtime 变更触发重摄取）");
    }

    /* ---------------- /upload 非法输入拒绝 ---------------- */

    @Test
    void upload_blankFile_rejected() {
        enableService();
        MockMultipartFile empty = new MockMultipartFile("file", "a.md", "text/markdown", new byte[0]);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.upload(empty, null));
        assertTrue(ex.getMessage().contains("为空"));
    }

    @Test
    void upload_nonMarkdownExtension_rejected() {
        enableService();
        MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf", "application/pdf",
                "内容".getBytes());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.upload(pdf, null));
        assertTrue(ex.getMessage().contains(".md/.txt"));
    }

    @Test
    void upload_oversize_rejected() {
        enableService();
        byte[] oversize = new byte[(int) (KnowledgeAdminController.MAX_UPLOAD_BYTES + 1)];
        MockMultipartFile big = new MockMultipartFile("file", "big.md", "text/markdown", oversize);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.upload(big, null));
        assertTrue(ex.getMessage().contains("1MB"));
    }

    @Test
    void upload_kbWhitelistViolation_rejected() {
        enableService();
        MockMultipartFile file = new MockMultipartFile("file", "a.md", "text/markdown",
                "内容".getBytes());

        // 大写 / 路径分隔符 / 空格 均不在 [a-z0-9-_] 白名单内
        for (String badKb : List.of("Java", "a/b", "a b", "..", "a.b")) {
            assertThrows(IllegalArgumentException.class, () -> controller.upload(file, badKb),
                    "kb=" + badKb + " 应被拒绝");
        }
    }

    @Test
    void upload_pathTraversalOrHiddenFilename_rejected() {
        enableService();
        // 路径分隔符 / 穿越段 / 隐藏文件均拒绝，落盘路径恒在知识目录内
        for (String badName : List.of("../evil.md", "sub/evil.md", "sub\\evil.md", ".hidden.md", "a..b.md")) {
            MockMultipartFile file = new MockMultipartFile("file", badName, "text/markdown",
                    "内容".getBytes());
            assertThrows(IllegalArgumentException.class, () -> controller.upload(file, null),
                    "文件名 " + badName + " 应被拒绝");
        }
    }

    @Test
    void upload_serviceDisabled_throws503_withoutWritingDisk() {
        when(knowledgeServiceProvider.getIfAvailable()).thenReturn(null);
        MockMultipartFile file = new MockMultipartFile("file", "a.md", "text/markdown",
                "内容".getBytes());

        assertThrows(KnowledgeAdminController.KnowledgeDisabledException.class,
                () -> controller.upload(file, null));
        assertTrue(!Files.exists(tempDir.resolve("a.md")), "未启用时不应落盘");
    }
}
