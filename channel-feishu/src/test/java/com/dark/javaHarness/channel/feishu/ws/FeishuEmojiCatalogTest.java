package com.dark.javaHarness.channel.feishu.ws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 表情包选择器：标记剥离、按分类前缀取图、概率与各类降级。
 *
 * <p>用真实临时目录而不是 mock 文件系统：这个类的核心行为就是「索引缺失时建空文件」
 * 「图片缺失时跳过」，把 IO 抽掉就什么都没验到。
 */
class FeishuEmojiCatalogTest {

    @TempDir
    Path tempDir;

    private FeishuProperties.Ws.Emoji config() {
        FeishuProperties.Ws.Emoji cfg = new FeishuProperties.Ws.Emoji();
        cfg.setEnabled(true);
        cfg.setDir(tempDir.toString());
        cfg.setIndexFile(tempDir.resolve("emoji-index.json").toString());
        cfg.setProbability(1.0);
        return cfg;
    }

    @Test
    void disabled_doesNothingAndDoesNotTouchIndex() {
        FeishuProperties.Ws.Emoji cfg = config();
        cfg.setEnabled(false);

        FeishuEmojiCatalog catalog = new FeishuEmojiCatalog(cfg);

        assertNull(catalog.pick("happy"));
        assertFalse(Files.exists(tempDir.resolve("emoji-index.json")), "关闭时不该创建映射文件");
    }

    @Test
    void missingIndex_isCreatedEmptyAndPicksNothing() throws Exception {
        FeishuEmojiCatalog catalog = new FeishuEmojiCatalog(config());

        assertNull(catalog.pick("happy"));
        Path index = tempDir.resolve("emoji-index.json");
        assertTrue(Files.isRegularFile(index), "缺失时应自动创建空映射");
        assertEquals("{\n}\n", Files.readString(index, StandardCharsets.UTF_8));
    }

    @Test
    void pick_matchesCategoryPrefixAndReturnsBytes() throws Exception {
        byte[] image = {9, 8, 7};
        Files.write(tempDir.resolve("happy_01.png"), image);
        Files.writeString(tempDir.resolve("emoji-index.json"),
                "{\"happy_01\":\"happy_01.png\",\"sad_01\":\"sad_01.png\"}", StandardCharsets.UTF_8);

        FeishuEmojiCatalog.Picked picked = new FeishuEmojiCatalog(config()).pick("happy");

        assertNotNull(picked);
        assertEquals("happy_01", picked.name());
        assertArrayEquals(image, picked.bytes());
    }

    @Test
    void pick_unknownCategory_returnsNull() throws Exception {
        Files.writeString(tempDir.resolve("emoji-index.json"),
                "{\"happy_01\":\"happy_01.png\"}", StandardCharsets.UTF_8);

        assertNull(new FeishuEmojiCatalog(config()).pick("angry"));
        assertNull(new FeishuEmojiCatalog(config()).pick("none"));
        assertNull(new FeishuEmojiCatalog(config()).pick(" "));
    }

    @Test
    void pick_probabilityZero_neverSends() throws Exception {
        Files.write(tempDir.resolve("happy_01.png"), new byte[] {1});
        Files.writeString(tempDir.resolve("emoji-index.json"),
                "{\"happy_01\":\"happy_01.png\"}", StandardCharsets.UTF_8);
        FeishuProperties.Ws.Emoji cfg = config();
        cfg.setProbability(0.0);

        assertNull(new FeishuEmojiCatalog(cfg).pick("happy"));
    }

    @Test
    void pick_missingImageFile_returnsNull() throws Exception {
        Files.writeString(tempDir.resolve("emoji-index.json"),
                "{\"happy_01\":\"absent.png\"}", StandardCharsets.UTF_8);

        assertNull(new FeishuEmojiCatalog(config()).pick("happy"));
    }

    @Test
    void stripMarker_removesLastMarkerAndReturnsCategory() {
        FeishuEmojiCatalog.ParsedReply parsed =
                FeishuEmojiCatalog.stripMarker("好耶！\n[emoji:happy]");

        assertEquals("好耶！", parsed.text());
        assertEquals("happy", parsed.category());
    }

    @Test
    void stripMarker_keepsTextWhenNoMarker() {
        FeishuEmojiCatalog.ParsedReply parsed = FeishuEmojiCatalog.stripMarker("普通回答");

        assertEquals("普通回答", parsed.text());
        assertNull(parsed.category());
    }

    @Test
    void stripMarker_noneIsReturnedAsCategory() {
        FeishuEmojiCatalog.ParsedReply parsed = FeishuEmojiCatalog.stripMarker("好的\n[emoji:none]");

        assertEquals("好的", parsed.text());
        assertEquals("none", parsed.category());
    }

    @Test
    void stripMarker_onlyMarker_leavesEmptyText() {
        FeishuEmojiCatalog.ParsedReply parsed = FeishuEmojiCatalog.stripMarker("[emoji:happy]");

        assertTrue(parsed.text().isEmpty());
        assertEquals("happy", parsed.category());
    }
}
