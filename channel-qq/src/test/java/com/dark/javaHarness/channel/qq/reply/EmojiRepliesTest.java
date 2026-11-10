package com.dark.javaHarness.channel.qq.reply;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.channel.qq.client.NapCatProperties;
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
 * EmojiReplies 单测：映射 JSON 加载与缺失/损坏降级、按分类前缀随机取图（含 base64:// 内联断言）、
 * none/空/未知分类不发送、文件缺失跳过、概率放行边界、enabled=false 零行为。
 */
class EmojiRepliesTest {

    /** 测试映射（结构同 emojis/emoji-index.json：表情名 → 图片文件） */
    private static final String INDEX_JSON = """
            {
              "happy_01": "开心转圈.gif",
              "happy_02": "开心大笑.gif",
              "sad_02": "流泪猫猫头.png",
              "poke_04": "戳一戳.gif"
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
        // 每张图片内容唯一：image 段用 base64 内联，内容相同则无法区分选中了哪张图
        Files.writeString(emojisDir.resolve("开心转圈.gif"), "gif-happy-01");
        Files.writeString(emojisDir.resolve("开心大笑.gif"), "gif-happy-02");
        Files.writeString(emojisDir.resolve("流泪猫猫头.png"), "png-sad");
        Files.writeString(emojisDir.resolve("戳一戳.gif"), "gif-poke");
        indexFile = tempDir.resolve("emoji-index.json");
        Files.writeString(indexFile, INDEX_JSON, StandardCharsets.UTF_8);
        props = new NapCatProperties();
        props.getEmoji().setEnabled(true);
        props.getEmoji().setDir(emojisDir.toString());
        props.getEmoji().setIndexFile(indexFile.toString());
        props.getEmoji().setProbability(1.0);
        props.getEmoji().setSendDelayMs(800);
    }

    /** 期望的 image 段 file 值：base64://<指定表情文件的完整内容编码> */
    private String b64Of(String emojiFileName) throws IOException {
        return "base64://" + Base64.getEncoder()
                .encodeToString(Files.readAllBytes(emojisDir.resolve(emojiFileName)));
    }

    @Test
    void pickByCategory_singleCandidate_returnsImageSegmentWithInlineBase64() throws IOException {
        EmojiReplies replies = new EmojiReplies(props);
        MessageSegment seg = replies.pickByCategory("sad");
        assertNotNull(seg);
        assertEquals("image", seg.type());
        assertEquals(b64Of("流泪猫猫头.png"), seg.data().get("file"));
    }

    @Test
    void pickByCategory_multiCandidate_randomPickWithinCategory() throws IOException {
        EmojiReplies replies = new EmojiReplies(props);
        replies.random = new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        assertEquals(b64Of("开心转圈.gif"),
                String.valueOf(replies.pickByCategory("happy").data().get("file")));
        replies.random = new Random() {
            @Override
            public int nextInt(int bound) {
                return 1;
            }
        };
        assertEquals(b64Of("开心大笑.gif"),
                String.valueOf(replies.pickByCategory("HAPPY").data().get("file")));
    }

    @Test
    void pickByCategory_noneBlankUnknown_returnsNull() {
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickByCategory("none"));
        assertNull(replies.pickByCategory(""));
        assertNull(replies.pickByCategory("unknown"));
        assertNull(replies.pickByCategory(null));
    }

    /**
     * 映射表不进 git：缺失时自动补一个空映射，并降级为空（不发送任何表情）。
     * 空文件的价值是「告诉使用者该往哪填」，所以必须真的落盘。
     */
    @Test
    void loadIndex_missingFile_createsEmptyIndexAndDegrades() throws IOException {
        Path absent = tempDir.resolve("absent.json");
        props.getEmoji().setIndexFile(absent.toString());

        EmojiReplies replies = new EmojiReplies(props);

        assertNull(replies.pickByCategory("happy"));
        assertNull(replies.pickByCategory("sad"));
        assertTrue(Files.isRegularFile(absent), "缺失时应自动创建空映射文件");
        assertEquals("{}", Files.readString(absent, StandardCharsets.UTF_8).replaceAll("\\s", ""));
    }

    /** 父目录也不存在时一并创建：首次部署常见（只配了路径，目录还没建） */
    @Test
    void loadIndex_missingParentDir_createsDirectories() {
        Path nested = tempDir.resolve("a/b/emoji-index.json");
        props.getEmoji().setIndexFile(nested.toString());

        new EmojiReplies(props);

        assertTrue(Files.isRegularFile(nested), "应连同父目录一起创建");
    }

    /** 无法创建（只读挂载、路径被文件占用）时仍要正常启动，只降级 */
    @Test
    void loadIndex_cannotCreate_doesNotThrow() throws IOException {
        // 父路径是个普通文件：创建目录必然失败，用来模拟只读/非法路径
        Files.writeString(tempDir.resolve("blocker"), "not a dir", StandardCharsets.UTF_8);
        props.getEmoji().setIndexFile(tempDir.resolve("blocker/emoji-index.json").toString());

        assertDoesNotThrow(() -> new EmojiReplies(props));
    }

    @Test
    void loadIndex_brokenJson_degradesToEmptyWithoutThrow() throws IOException {
        Files.writeString(indexFile, "{ 这不是合法 JSON", StandardCharsets.UTF_8);
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickByCategory("happy"));
        assertNull(replies.pickByCategory("sad"));
    }

    @Test
    void pickByCategory_missingEmojiFile_skipsGracefully() throws IOException {
        Files.writeString(indexFile, """
                { "ghost_99": "不存在的图.png" }
                """, StandardCharsets.UTF_8);
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickByCategory("ghost"));
    }

    @Test
    void pickByCategory_probabilityZeroBlocksAndHalfRollsDecide() {
        props.getEmoji().setProbability(0.0);
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickByCategory("happy"));
        // 概率 0.5：摇号 >= 0.5 拦下，< 0.5 放行（注入固定 Random 做确定性断言）
        props.getEmoji().setProbability(0.5);
        replies.random = new Random() {
            @Override
            public double nextDouble() {
                return 0.9;
            }
        };
        assertNull(replies.pickByCategory("happy"));
        replies.random = new Random() {
            @Override
            public double nextDouble() {
                return 0.1;
            }

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        assertNotNull(replies.pickByCategory("happy"));
    }

    @Test
    void pickByCategory_disabled_zeroBehaviorEvenWithBrokenConfig() {
        props.getEmoji().setEnabled(false);
        // 关闭时不读映射文件/目录：index-file、dir 即使缺失也零行为
        props.getEmoji().setIndexFile(tempDir.resolve("absent.json").toString());
        props.getEmoji().setDir(tempDir.resolve("absent-dir").toString());
        EmojiReplies replies = new EmojiReplies(props);
        assertNull(replies.pickByCategory("happy"));
    }
}
