package com.dark.javaHarness.music;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 本地曲库：扫目录、按关键词点歌、无关键词时随机且尽量不重复。
 *
 * <p>每次调用都重新扫目录（而不是缓存 + 文件监听）：曲库就几十首，扫描成本是一次
 * {@code Files.list}，换来的是「丢一首进去立刻能点」——比缓存失效策略省心得多。
 */
@Component
public class MusicLibrary {

    private static final Logger log = LoggerFactory.getLogger(MusicLibrary.class);

    /** 能放的扩展名：容器格式不限，转码统一交给渠道（ffmpeg 能解的都能进） */
    private static final Set<String> EXTENSIONS = Set.of(".mp3", ".flac", ".m4a", ".wav", ".ogg", ".opus", ".aac");

    /** 文件名前缀的序号：「01. 陶喆 - 讨厌红楼梦」→「陶喆 - 讨厌红楼梦」 */
    private static final String TRACK_PREFIX = "^\\d+[.、\\-\\s]*";

    private final MusicProperties props;
    private final Random random;

    /** 上一次点过的曲目：随机时避开它，避免「随机」连放同一首 */
    private volatile String lastPlayed;

    @org.springframework.beans.factory.annotation.Autowired
    public MusicLibrary(MusicProperties props) {
        this(props, new Random());
    }

    /** 测试用：固定随机种子 */
    MusicLibrary(MusicProperties props, Random random) {
        this.props = props;
        this.random = random;
    }

    /** 曲目：展示用标题（去序号去扩展名）+ 文件 */
    public record Track(String title, Path file, long sizeBytes) {
    }

    /** 当前曲库（目录不存在/不可读时返回空表，由调用方给出可读提示） */
    public List<Track> tracks() {
        Path dir = Path.of(props.getDir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> EXTENSIONS.contains(extensionOf(p)))
                    .map(p -> new Track(titleOf(p), p, sizeOf(p)))
                    .sorted(Comparator.comparing(Track::title))
                    .toList();
        } catch (IOException e) {
            log.warn("[music] 曲库目录读取失败 dir={}：{}", dir.toAbsolutePath(), e.toString());
            return List.of();
        }
    }

    /**
     * 点歌。
     *
     * @param keyword 歌名/文件名关键词，忽略大小写与空格；为 null/空时随机
     * @return 命中曲目；曲库为空、关键词无命中都返回空，由调用方解释原因
     */
    public Optional<Track> pick(String keyword) {
        List<Track> all = tracks();
        if (all.isEmpty()) {
            return Optional.empty();
        }
        if (keyword == null || keyword.isBlank()) {
            return Optional.of(randomExceptLast(all));
        }
        String needle = normalize(keyword);
        List<Track> hits = all.stream()
                .filter(t -> normalize(t.title()).contains(needle) || normalize(t.file().getFileName().toString()).contains(needle))
                .toList();
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(hits.get(random.nextInt(hits.size())));
    }

    /** 记住这次点了哪首（供下次随机时避开） */
    public void markPlayed(Track track) {
        this.lastPlayed = track.file().toString();
    }

    private Track randomExceptLast(List<Track> all) {
        if (all.size() == 1) {
            return all.get(0);
        }
        List<Track> candidates = all.stream()
                .filter(t -> !t.file().toString().equals(lastPlayed))
                .toList();
        List<Track> pool = candidates.isEmpty() ? all : candidates;
        return pool.get(random.nextInt(pool.size()));
    }

    /** 展示标题：去扩展名、去序号；`_` 换成空格（文件名常见分隔符） */
    static String titleOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replaceFirst(TRACK_PREFIX, "").replace('_', ' ').trim();
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    /** 匹配用归一化：全角空格、大小写、多余空白都不该影响点歌 */
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('\u3000', ' ').replaceAll("\\s+", " ").trim();
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }
}
