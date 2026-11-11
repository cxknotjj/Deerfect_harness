package com.dark.javaHarness.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 曲库单测：扫目录、去序号、关键词匹配、随机不连播。
 *
 * <p>用空文件而不是真音频：本类只关心文件名与目录，读字节的事在工具层。
 */
class MusicLibraryTest {

    @TempDir
    Path dir;

    private MusicProperties props;

    @BeforeEach
    void setUp() throws IOException {
        props = new MusicProperties();
        props.setDir(dir.toString());
        Files.write(dir.resolve("01. 陶喆 - 讨厌红楼梦.mp3"), new byte[] {1});
        Files.write(dir.resolve("02. EXO - Baby Don't Cry.flac"), new byte[] {1});
        Files.write(dir.resolve("readme.txt"), new byte[] {1});
    }

    @Test
    void tracks_ignoresNonAudioAndStripsTrackNumber() {
        List<MusicLibrary.Track> tracks = new MusicLibrary(props).tracks();

        assertEquals(2, tracks.size(), "txt 不该进曲库");
        assertEquals(java.util.Set.of("EXO - Baby Don't Cry", "陶喆 - 讨厌红楼梦"),
                tracks.stream().map(MusicLibrary.Track::title).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void pick_byKeyword_matchesTitleAndFileName() {
        MusicLibrary library = new MusicLibrary(props);

        assertEquals("陶喆 - 讨厌红楼梦", library.pick("讨厌").orElseThrow().title());
        assertEquals("陶喆 - 讨厌红楼梦", library.pick("陶喆").orElseThrow().title());
        assertEquals("EXO - Baby Don't Cry", library.pick("exo").orElseThrow().title(), "大小写不敏感");
    }

    @Test
    void pick_unknownKeyword_isEmpty() {
        assertTrue(new MusicLibrary(props).pick("周杰伦").isEmpty());
    }

    @Test
    void pick_noKeyword_isRandomButSkipsLastPlayed() {
        MusicLibrary library = new MusicLibrary(props, new Random(42));
        MusicLibrary.Track first = library.pick(null).orElseThrow();
        library.markPlayed(first);

        for (int i = 0; i < 5; i++) {
            MusicLibrary.Track next = library.pick(null).orElseThrow();
            assertTrue(!next.title().equals(first.title()), "随机不该连放同一首");
        }
    }

    @Test
    void pick_singleTrack_stillReturnsIt() throws IOException {
        Path solo = Files.createTempDirectory("music-solo");
        Files.write(solo.resolve("only.mp3"), new byte[] {1});
        MusicProperties only = new MusicProperties();
        only.setDir(solo.toString());

        MusicLibrary library = new MusicLibrary(only);
        MusicLibrary.Track track = library.pick(null).orElseThrow();
        library.markPlayed(track);

        assertEquals("only", track.title());
        assertTrue(library.pick(null).isPresent(), "只有一首时仍然要能点");
    }

    @Test
    void tracks_missingDir_isEmpty() {
        MusicProperties missing = new MusicProperties();
        missing.setDir(dir.resolve("nope").toString());

        assertTrue(new MusicLibrary(missing).tracks().isEmpty());
    }

    @Test
    void titleOf_normalizesUnderscoreAndPrefix() {
        assertEquals("a b", MusicLibrary.titleOf(Path.of("03_a_b.mp3")));
        assertEquals("song", MusicLibrary.titleOf(Path.of("song.m4a")));
        assertEquals("x", MusicLibrary.titleOf(Path.of("x")));
    }

    @Test
    void pick_returnsPathUnderConfiguredDir() {
        Optional<MusicLibrary.Track> track = new MusicLibrary(props).pick("讨厌");

        assertTrue(track.orElseThrow().file().startsWith(dir));
    }
}
