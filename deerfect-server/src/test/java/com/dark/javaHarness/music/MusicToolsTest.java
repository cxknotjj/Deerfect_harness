package com.dark.javaHarness.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.contract.api.VoiceArtifactStore;
import com.dark.javaHarness.tool.trace.ToolCallTracer;
import com.dark.javaHarness.voice.InMemoryVoiceArtifactStore;
import com.dark.javaHarness.voice.TtsProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 点歌工具单测：落库的 format 必须是 music（渠道据此选转码参数），失败分支给可读提示。
 */
class MusicToolsTest {

    private static final String TURN = "turn-1";

    @TempDir
    Path dir;

    private MusicProperties props;
    private VoiceArtifactStore artifacts;

    @BeforeEach
    void setUp() throws IOException {
        props = new MusicProperties();
        props.setDir(dir.toString());
        artifacts = new InMemoryVoiceArtifactStore(new TtsProperties());
    }

    @Test
    void play_matchingSong_storesMusicArtifact() throws IOException {
        Files.write(dir.resolve("01. 陶喆 - 讨厌红楼梦.mp3"), new byte[] {7, 8, 9});
        MusicTools tools = tools();

        String result = tools.playMusic("讨厌", context());

        assertTrue(result.contains("正在播放《陶喆 - 讨厌红楼梦》"), result);
        VoiceArtifactStore.VoiceArtifact artifact = artifacts.findByTurn(TURN).orElseThrow();
        assertEquals("music", artifact.format(), "渠道靠 format=music 选音乐参数");
        assertEquals(3, artifact.audio().length);
    }

    @Test
    void play_unknownSong_listsAvailable() throws IOException {
        Files.write(dir.resolve("a.mp3"), new byte[] {1});
        Files.write(dir.resolve("b.mp3"), new byte[] {1});

        String result = tools().playMusic("周杰伦", context());

        assertTrue(result.contains("没有匹配"), result);
        assertTrue(result.contains("a") && result.contains("b"), "无命中时要把可点的列出来");
        assertTrue(artifacts.findByTurn(TURN).isEmpty(), "没点到就不该产生工件");
    }

    @Test
    void play_emptyLibrary_saysSo() {
        String result = tools().playMusic(null, context());

        assertTrue(result.contains("曲库是空的"), result);
    }

    @Test
    void play_oversizeFile_isRejected() throws IOException {
        Files.write(dir.resolve("big.mp3"), new byte[10]);
        props.setMaxBytes(4);

        String result = tools().playMusic("big", context());

        assertTrue(result.contains("超过上限"), result);
        assertTrue(artifacts.findByTurn(TURN).isEmpty());
    }

    @Test
    void play_disabled_doesNothing() {
        props.setEnabled(false);

        String result = tools().playMusic(null, context());

        assertTrue(result.contains("未启用"), result);
    }

    @Test
    void play_missingTurn_isRejected() throws IOException {
        Files.write(dir.resolve("a.mp3"), new byte[] {1});

        String result = tools().playMusic(null, new ToolContext(Map.of()));

        assertTrue(result.contains("无法关联本轮回复"), result);
    }

    private MusicTools tools() {
        return new MusicTools(props, new MusicLibrary(props), artifacts);
    }

    private static ToolContext context() {
        return new ToolContext(Map.of(ToolCallTracer.CTX_TURN_ID, TURN));
    }
}
