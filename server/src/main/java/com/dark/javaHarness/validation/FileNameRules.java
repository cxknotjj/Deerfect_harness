package com.dark.javaHarness.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 上传文件名 / 知识库命名规则（无状态纯函数）：
 * 扩展名仅 .md/.txt、单文件 ≤1MB、kb 白名单 {@code [a-z0-9_-]}、
 * 文件名拒绝路径分隔符与隐藏文件。非法输入抛 IllegalArgumentException（400）。
 */
public final class FileNameRules {

    /** 单文件上传上限（1MB） */
    public static final long MAX_UPLOAD_BYTES = 1024 * 1024;

    /** 知识库目录名白名单 */
    private static final Pattern KB_NAME = Pattern.compile("^[a-z0-9_-]+$");

    private FileNameRules() {
    }

    /**
     * 校验并归一化上传文件名（strip 后返回）。
     *
     * @param filename 原始文件名（可为 null/空白）
     * @param size     文件字节数
     * @throws IllegalArgumentException 文件名缺失/扩展名非法/含路径成分/超过大小上限
     */
    public static String requireUpload(String filename, long size) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名缺失");
        }
        String name = filename.strip();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean validExtension = (lower.endsWith(".md") && lower.length() > 3)
                || (lower.endsWith(".txt") && lower.length() > 4);
        if (!validExtension) {
            throw new IllegalArgumentException("仅支持 .md/.txt 文档: " + name);
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.contains("..") || name.startsWith(".")) {
            throw new IllegalArgumentException("文件名非法（拒绝路径分隔符/隐藏文件）: " + name);
        }
        if (size > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("文件超过 1MB 上限: " + size + " bytes");
        }
        return name;
    }

    /**
     * 归一化知识库目录名：空白返回 null（落到知识目录根），非空则校验白名单。
     *
     * @throws IllegalArgumentException 名称含白名单外字符
     */
    public static String normalizeKb(String kb) {
        if (kb == null || kb.isBlank()) {
            return null;
        }
        String normalized = kb.strip();
        if (!KB_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("kb 仅允许小写字母/数字/-/_: " + normalized);
        }
        return normalized;
    }
}
