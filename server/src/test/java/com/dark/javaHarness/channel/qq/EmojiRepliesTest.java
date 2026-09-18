package com.dark.javaHarness.channel.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * EmojiReplies 单测：映射 JSON 加载与缺失/损坏降级、tag contains 命中（含 base64:// 内联断言）、
 * 多命中随机取一、句尾 ~/！/! 兜底、tag 优先于句尾符号、表情文件缺失跳过、
 * 概率放行边界、enabled=false 零行为。
 */
class EmojiRepliesTest {

    /** 测试映射（结构同 channel/emoji-index.json；cheer_00 空 tags 仅作句尾兜底目标，不会被 tag 命中） */
    private static final String INDEX_JSON = """
            {
              "happy_01": { "path": "开心转圈.gif", "tags": ["开心", "激动", "好耶", "太棒了"] },
              "sad_02": { "path": "流泪猫猫头.png", "tags": ["伤心", "委屈", "难过", "呜呜"] },
              "poke_04": { "path": "戳一戳.gif", "tags": ["在吗", "理我", "戳", "出来"] },
              "cheer_00": { "path": "兜底比心.png", "tags": [] }
            }
            """;

    @TempDir
    Path tempDir;

    private NapCatProperties props;
    private Path emojisDir;
    private Path indexFile;

    @BeforeEach
    void setUp() throws IOException {
        emojisDir = Files.createDirectories(tempDir.resolve("emojis"));
        // 每张图片内容唯一：image 段用 base64 内联，内容相同则无法区分命中了哪张图
        Files.writeString(emojisDir.resolve("开心转圈.gif"), "gif-happy");
        Files.writeString(emojisDir.resolve("流泪猫猫头.png"), "png-sad");
        Files.writeString(emojisDir.resolve("戳一戳.gif"), "gif-poke");
        Files.writeString(emojisDir.resolve("兜底比心.png"), "png-cheer");
        indexFile = tempDir.resolve("emoji-index.json");
        Files.writeString(indexFile, INDEX_JSON, StandardCharsets.UTF_8);
        props = new NapCatProperties();
        props.getEmoji().setEnabled(true);
        props.getEmoji().setDir(emojisDir.toString());
        props.getEmoji().setIndexFile(indexFile.toString());
        props.getEmoji().setMaxPerReply(0);
        props.getEmoji().setProbability(1.0);
        props.getEmoji().setSendDelayMs(800);
        props.getEmoji().setCheerEmoji("cheer_00");
    }

    /** 期望的 image 段 file 值：base64://<指定表情文件的完整内容编码> */
    private String b64Of(String emojiFileName) throws IOException {
        return "base64://" + Base64.getEncoder()
                .encodeToString(Files.readAllBytes(emojisDir.resolve(emojiFileName)));
    }

    @Test
    void pickFor_tagHit_returnsImageSegmentWithInlineBase64() throws IOException {
        EmojiReplies replies = new EmojiReplies(props);
        MessageSegment seg = replies.pickFor("今天好开心");
        assertNotNull(seg);
        assertEquals("image", seg.type());
        assertEquals(b64Of("开心转圈.gif"), seg.data().get("file"));
    }

    @Test
    void loadIndex_missingFile_degradesToEmptyWithoutThrow() {
        props.getEmoji().setIndexFile(tempDir.resolve("absent.json").toString());
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickFor("好开心"));
        assertNull(replies.pickFor("陪我玩嘛~"));
    }

    @Test
    void loadIndex_brokenJson_degradesToEmptyWithoutThrow() throws IOException {
        Files.writeString(indexFile, "{ 这不是合法 JSON", StandardCharsets.UTF_8);
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickFor("好开心"));
        assertNull(replies.pickFor("来玩啊！"));
    }

    @Test
    void pickFor_cheerFallback_onTildeAndBangEndings() throws IOException {
        EmojiReplies replies = new EmojiReplies(props);
        for (String chunk : new String[] {"陪我玩嘛~", "来玩啊！", "come on!"}) {
            MessageSegment seg = replies.pickFor(chunk);
            assertNotNull(seg, chunk);
            assertEquals(b64Of("兜底比心.png"), String.valueOf(seg.data().get("file")), chunk);
        }
        // 非句尾符号（句号结尾 / 符号在中间）不触发兜底
        assertNull(replies.pickFor("普通陈述句。"));
        assertNull(replies.pickFor("中间!的感叹号不算"));
    }

    @Test
    void pickFor_tagPriorityOverSentenceEndFallback() throws IOException {
        EmojiReplies replies = new EmojiReplies(props);
        MessageSegment seg = replies.pickFor("好开心呀！");
        assertNotNull(seg);
        // tag 命中 happy_01 优先于句尾 ！ 兜底的 cheer_00
        assertEquals(b64Of("开心转圈.gif"), String.valueOf(seg.data().get("file")));
    }

    @Test
    void pickFor_multiHit_randomPickAmongCandidates() throws IOException {
        EmojiReplies replies = new EmojiReplies(props);
        // 同时命中 happy_01（开心）与 poke_04（在吗）：注入固定 Random 验证随机取一
        replies.random = new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        assertEquals(b64Of("开心转圈.gif"),
                String.valueOf(replies.pickFor("好开心呀，在吗").data().get("file")));
        replies.random = new Random() {
            @Override
            public int nextInt(int bound) {
                return 1;
            }
        };
        assertEquals(b64Of("戳一戳.gif"),
                String.valueOf(replies.pickFor("好开心呀，在吗").data().get("file")));
    }

    @Test
    void pickFor_missingEmojiFile_skipsGracefully() throws IOException {
        Files.writeString(indexFile, """
                { "ghost_99": { "path": "不存在的图.png", "tags": ["闹鬼"] } }
                """, StandardCharsets.UTF_8);
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickFor("好闹鬼啊"));
    }

    @Test
    void pickFor_cheerEmojiNotInIndex_returnsNullEveryTime() {
        props.getEmoji().setCheerEmoji("nope_00");
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickFor("陪我玩嘛~"));
        assertNull(replies.pickFor("再来一次~"));
    }

    @Test
    void pickFor_probabilityZeroBlocksAndHalfRollsDecide() {
        props.getEmoji().setProbability(0.0);
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickFor("好开心"));
        // 概率 0.5：摇号 >= 0.5 拦下，< 0.5 放行（注入固定 Random 做确定性断言）
        props.getEmoji().setProbability(0.5);
        replies.random = new Random() {
            @Override
            public double nextDouble() {
                return 0.9;
            }
        };
        assertNull(replies.pickFor("好开心"));
        replies.random = new Random() {
            @Override
            public double nextDouble() {
                return 0.1;
            }
        };
        assertNotNull(replies.pickFor("好开心"));
    }

    @Test
    void pickFor_disabled_zeroBehaviorEvenWithBrokenConfig() {
        props.getEmoji().setEnabled(false);
        // 关闭时不读映射文件/目录：index-file、dir 即使缺失也零行为
        props.getEmoji().setIndexFile(tempDir.resolve("absent.json").toString());
        props.getEmoji().setDir(tempDir.resolve("absent-dir").toString());
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickFor("好开心"));
        assertNull(replies.pickFor("陪我玩嘛~"));
    }
}
