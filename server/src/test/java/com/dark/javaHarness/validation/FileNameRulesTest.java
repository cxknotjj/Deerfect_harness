package com.dark.javaHarness.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 上传文件名 / kb 命名纯规则单测 */
class FileNameRulesTest {

    @Test
    void requireUpload_trimsAndAcceptsMdAndTxt() {
        assertEquals("report.md", FileNameRules.requireUpload(" report.md ", 10));
        assertEquals("notes.txt", FileNameRules.requireUpload("notes.txt", 10));
    }

    @Test
    void requireUpload_rejectsMissingOrBadExtension() {
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.requireUpload(null, 10));
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.requireUpload("  ", 10));
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.requireUpload(".md", 10));
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.requireUpload("a.exe", 10));
    }

    @Test
    void requireUpload_rejectsPathTraversalAndHiddenFile() {
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.requireUpload("a/b.md", 10));
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.requireUpload("a\\b.md", 10));
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.requireUpload("../x.md", 10));
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.requireUpload(".hidden.md", 10));
    }

    @Test
    void requireUpload_rejectsOversize() {
        long tooBig = FileNameRules.MAX_UPLOAD_BYTES + 1;
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.requireUpload("big.md", tooBig));
    }

    @Test
    void normalizeKb_blankToNullAndWhitelist() {
        assertNull(FileNameRules.normalizeKb(null));
        assertNull(FileNameRules.normalizeKb("  "));
        assertEquals("kb-1_a", FileNameRules.normalizeKb(" kb-1_a "));
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.normalizeKb("KB 1"));
        assertThrows(IllegalArgumentException.class, () -> FileNameRules.normalizeKb("../x"));
    }
}
