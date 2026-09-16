package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.dto.KnowledgeSource;
import com.dark.javaHarness.domain.dto.PageResult;
import com.dark.javaHarness.domain.entity.KbDocumentEntity;
import com.dark.javaHarness.knowledge.KnowledgeDocumentScanner;
import com.dark.javaHarness.knowledge.KnowledgeService;
import com.dark.javaHarness.knowledge.KnowledgeSyncView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库管理接口（RAG 知识面，CLI/调试/管理页用）：
 *
 * <p>POST   /api/knowledge/sync            增量摄取 knowledge/ 目录（扫描→比对 mtime→切分→嵌入→入向量库）
 * <p>GET    /api/knowledge/documents       摄取台账分页（doc_name/title/mtime/chunk_count/status）
 * <p>GET    /api/knowledge/search?q=       调试用检索（full=true 额外返回片段原文 text，默认向后兼容不返回）
 * <p>POST   /api/knowledge/upload          multipart 上传 .md/.txt（≤1MB）到指定 kb 子目录，只落盘不摄取
 * <p>DELETE /api/knowledge/documents/{name} 删除指定文档（向量 chunk + 台账行）
 *
 * <p>知识库未启用（app.knowledge.enabled=false）时依赖经 ObjectProvider 解析为空，
 * 请求返回 503（GlobalExceptionHandler 统一 {code,message}）；向量库不可用时 sync 抛
 * IllegalStateException、search 静默空（降级口径对齐 KnowledgeServiceImpl）；上传参数
 * 非法抛 IllegalArgumentException（全局映射 400）。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeAdminController {

    /** 上传单文件大小上限（与 spring.servlet.multipart.max-file-size 一致） */
    static final long MAX_UPLOAD_BYTES = 1024 * 1024;

    /** kb 归库子目录白名单：小写字母/数字/-/_（防路径穿越，kb 拼接目录前唯一闸门） */
    private static final Pattern KB_PATTERN = Pattern.compile("^[a-z0-9_-]+$");

    private final ObjectProvider<KnowledgeService> knowledgeService;
    private final KnowledgeDocumentScanner scanner;

    public KnowledgeAdminController(ObjectProvider<KnowledgeService> knowledgeService,
                                    KnowledgeDocumentScanner scanner) {
        this.knowledgeService = knowledgeService;
        this.scanner = scanner;
    }

    /** 增量摄取：knowledge/ 目录 .md/.txt 全量扫描，仅 mtime 变更的文档重嵌入 */
    @PostMapping("/sync")
    public KnowledgeSyncView sync() {
        return requireService().sync();
    }

    /** 摄取台账分页（page 从 1 起，size 默认 20 上限 200） */
    @GetMapping("/documents")
    public PageResult<KbDocumentEntity> documents(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size) {
        return requireService().list(page, size);
    }

    /**
     * 调试检索：直接检索（不走 prompt 注入），观察命中与相关度；可选按 kb 过滤（重复参数）。
     * full=true 额外返回片段原文 text（静态管理页展示用）；默认 false 保持 KnowledgeSource
     * 三字段现状（向后兼容）。
     */
    @GetMapping("/search")
    public List<?> search(@RequestParam("q") String query,
                          @RequestParam(value = "kb", required = false) List<String> kb,
                          @RequestParam(value = "full", required = false, defaultValue = "false") boolean full) {
        List<KnowledgeService.KnowledgeHit> hits = requireService().search(query, kb);
        return full ? hits
                : hits.stream().map(KnowledgeService.KnowledgeHit::toSource).toList();
    }

    /**
     * 上传知识文档：multipart 文件 + 可选 kb 表单字段（归库子目录名，空 = 根目录 default 库）。
     * 只落盘不自动摄取（前端随后调 sync，保持单一摄取代码路径）；重名覆盖写（mtime 变更
     * 自然触发重摄取）。文件名/扩展名/大小/kb 白名单校验见 {@link #validateAndResolve}。
     */
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(value = "kb", required = false) String kb) throws IOException {
        requireService();
        Path target = validateAndResolve(file, kb, scanner.dir());
        Files.createDirectories(target.getParent());
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        String docName = scanner.dir().relativize(target).toString().replace('\\', '/');
        System.getLogger(KnowledgeAdminController.class.getName()).log(System.Logger.Level.INFO,
                "[knowledge] 文档已上传：{0}（{1} bytes，待 sync 摄取）", docName, file.getSize());
        return docName;
    }

    /** 删除文档：向量 chunk（确定性 id 推导）+ 台账行一并删除；不存在返回 404 语义（false） */
    @DeleteMapping("/documents/{name}")
    public boolean delete(@PathVariable String name) {
        return requireService().delete(name);
    }

    /**
     * 上传校验与落盘路径解析：扩展名仅 .md/.txt、单文件 ≤1MB、kb 白名单
     * {@code [a-z0-9-_]}、文件名拒绝路径分隔符/隐藏文件——kb 与文件名双重约束下
     * 落盘路径恒在知识目录内（路径穿越不可达）。非法输入抛 IllegalArgumentException（400）。
     */
    static Path validateAndResolve(MultipartFile file, String kb, Path knowledgeDir) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名缺失");
        }
        filename = filename.strip();
        String lower = filename.toLowerCase(Locale.ROOT);
        boolean validExtension = (lower.endsWith(".md") && lower.length() > 3)
                || (lower.endsWith(".txt") && lower.length() > 4);
        if (!validExtension) {
            // 排除恰为 ".md"/".txt" 的裸扩展名文件名
            throw new IllegalArgumentException("仅支持 .md/.txt 文档: " + filename);
        }
        if (filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                || filename.contains("..") || filename.startsWith(".")) {
            throw new IllegalArgumentException("文件名非法（拒绝路径分隔符/隐藏文件）: " + filename);
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("文件超过 1MB 上限: " + file.getSize() + " bytes");
        }
        String normalizedKb = kb == null || kb.isBlank() ? null : kb.strip();
        if (normalizedKb != null && !KB_PATTERN.matcher(normalizedKb).matches()) {
            throw new IllegalArgumentException("kb 仅允许小写字母/数字/-/_: " + normalizedKb);
        }
        return normalizedKb == null
                ? knowledgeDir.resolve(filename)
                : knowledgeDir.resolve(normalizedKb).resolve(filename);
    }

    /** 知识库服务解析：未启用时 503（服务不可用），文案含开启方式 */
    private KnowledgeService requireService() {
        KnowledgeService service = knowledgeService.getIfAvailable();
        if (service == null) {
            throw new KnowledgeDisabledException();
        }
        return service;
    }

    /** 知识库未启用（app.knowledge.enabled=false）：503 + 开启指引 */
    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
    static class KnowledgeDisabledException extends RuntimeException {
        KnowledgeDisabledException() {
            super("知识库未启用：application.yaml 设置 app.knowledge.enabled=true 并配置 pgvector/嵌入端点后重启");
        }
    }
}
