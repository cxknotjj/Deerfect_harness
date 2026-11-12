package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TextSanitizer 纯函数单测：坏字符剥离（U+FFFD/控制字符/未配对代理）、URL 去查询参数
 * 与去重、聚合输入长度封顶。2026-10-06 聚合思考循环复盘的防线回归。
 */
class TextSanitizerTest {

    /* ---------------- stripBrokenChars ---------------- */

    @Test
    void strip_shouldRemoveReplacementChar() {
        // 复盘实证片段：正告其立<U+FFFD>z    <U+FFFD>停止
        String out = TextSanitizer.stripBrokenChars("正告其立\uFFFDz    \uFFFD停止侵权挑衅。");
        assertEquals("正告其立z    停止侵权挑衅。", out);
        assertFalse(out.contains("\uFFFD"));
    }

    @Test
    void strip_shouldKeepNewlineTabAndPairedSurrogates() {
        String emoji = "👍"; // 高低位代理配对（合法增补平面字符）
        String in = "a\nb\tc" + emoji + "d";
        assertEquals(in, TextSanitizer.stripBrokenChars(in));
    }

    @Test
    void strip_shouldDropLoneSurrogateAndControlChars() {
        assertEquals("abcdef", TextSanitizer.stripBrokenChars("ab\uD83Dcd\u0007ef\u007F"));
    }

    @Test
    void strip_nullAndEmpty_passthrough() {
        assertEquals(null, TextSanitizer.stripBrokenChars(null));
        assertEquals("", TextSanitizer.stripBrokenChars(""));
    }

    /* ---------------- dedupUrls ---------------- */

    @Test
    void dedup_shouldStripQueryAndShrinkDuplicates() {
        String in = "见 https://news.example.com/a?id=1&ref=x 的报道，再见 https://news.example.com/a?id=2&ref=y 的报道。";
        assertEquals("见 https://news.example.com/a 的报道，再见 news.example.com 的报道。",
                TextSanitizer.dedupUrls(in));
    }

    @Test
    void dedup_shouldTreatDifferentPathsAsDistinct() {
        assertEquals("https://a.com/x 和 https://a.com/y 都保留。",
                TextSanitizer.dedupUrls("https://a.com/x 和 https://a.com/y 都保留。"));
    }

    @Test
    void dedup_anchorStripped_andTrailingPunctuationRestored() {
        assertEquals("来源 https://a.com/x。",
                TextSanitizer.dedupUrls("来源 https://a.com/x#section。"));
    }

    /* ---------------- forAggregateInput ---------------- */

    @Test
    void aggregateInput_shouldCleanUrlsStripBrokenAndCap() {
        String longText = "重复 https://x.com/p?token=secret " + "正".repeat(3000)
                + " \uFFFD  https://x.com/p?token=other 又一段 " + "文".repeat(7000);
        String out = TextSanitizer.forAggregateInput(longText, 6000);
        assertFalse(out.contains("token="), "查询参数应剥离");
        assertTrue(out.contains("https://x.com/p"), "首次出现保留 origin+path");
        assertFalse(out.contains("\uFFFD"), "坏字符应剥离");
        assertEquals(6000 + "…[单条子任务结果超长已截断]".length(), out.length(), "超长应封顶到上限+标记");
        assertTrue(out.endsWith("…[单条子任务结果超长已截断]"));
    }

    @Test
    void aggregateInput_zeroCapMeansUnlimited() {
        assertEquals("abc", TextSanitizer.forAggregateInput("abc", 0));
    }
}
