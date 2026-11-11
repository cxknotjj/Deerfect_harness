package com.dark.javaHarness.music;

import com.dark.javaHarness.contract.api.VoiceArtifactStore;
import com.dark.javaHarness.tool.trace.ToolCallTracer;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 点歌工具：从本地曲库取一首歌放进语音工件库，由渠道发成语音消息。
 *
 * <p><b>为什么不在服务端转码</b>：渠道已经有一套 ffmpeg 调用（语音用它转 opus），
 * 在服务端再写一份等于两处维护同一件事。这里只把原始 mp3 放进工件库，format 标成
 * {@value #FORMAT_MUSIC}，渠道据此选「音乐参数」（高码率 + 立体声）转码。
 * 顺带的好处：QQ 渠道直接发 mp3 也能放，不用另做适配。
 *
 * <p>音乐有版权，曲库不进仓库也不进镜像，靠部署方自己放文件——见 {@link MusicProperties#getDir()}。
 */
@Component
public class MusicTools {

    private static final Logger log = LoggerFactory.getLogger(MusicTools.class);

    /**
     * 工件 format 取值：告诉渠道「这是音乐，按音乐参数转码」。
     * 语音走默认（mp3 + 语音参数），两者共用同一个工件库与同一条 voice:&lt;id&gt; 通道。
     */
    public static final String FORMAT_MUSIC = "music";

    private final MusicProperties props;
    private final MusicLibrary library;
    private final VoiceArtifactStore artifacts;

    public MusicTools(MusicProperties props, MusicLibrary library, VoiceArtifactStore artifacts) {
        this.props = props;
        this.library = library;
        this.artifacts = artifacts;
    }

    /** 供工具装配判断是否暴露本工具（关闭时模型看不到 play_music） */
    public boolean enabled() {
        return props.isEnabled();
    }

    /**
     * 点一首歌并用语音消息发出去。
     *
     * @param song 歌名关键词（可只写其中几个字）；留空表示随机
     */
    @Tool(name = "play_music", description = "从本地曲库点一首歌，用语音消息发给用户。"
            + "用户说「放首歌 / 来一首 / 点歌」时调用；用户点名了歌手或歌名就把关键词传进来，"
            + "没点名就留空随机。调用后只回一句简短的话，不要复述歌词或歌名列表")
    public String playMusic(
            @ToolParam(required = false, description = "歌名或歌手关键词，可只写其中几个字；留空=随机点一首") String song,
            ToolContext context) {
        if (!props.isEnabled()) {
            return "音乐功能未启用。";
        }
        String turnId = turnId(context);
        if (turnId == null) {
            return "无法关联本轮回复，未播放。";
        }
        List<MusicLibrary.Track> all = library.tracks();
        if (all.isEmpty()) {
            return "曲库是空的（目录 " + props.getDir() + "）。告诉用户先把音频文件放进去吧。";
        }
        Optional<MusicLibrary.Track> picked = library.pick(song);
        if (picked.isEmpty()) {
            return "曲库里没有匹配「" + song + "」的歌。可以点的有：" + titles(all) + "。";
        }
        MusicLibrary.Track track = picked.get();
        if (track.sizeBytes() > props.getMaxBytes()) {
            return "「" + track.title() + "」文件 " + track.sizeBytes() + " 字节，超过上限 "
                    + props.getMaxBytes() + "；换一首或先压缩。";
        }
        byte[] audio;
        try {
            audio = Files.readAllBytes(track.file());
        } catch (IOException e) {
            log.warn("[music] 读取失败 path={}：{}", track.file(), e.toString());
            return "读取「" + track.title() + "」失败：" + e.getMessage();
        }
        artifacts.put(turnId, track.title(), audio, FORMAT_MUSIC);
        library.markPlayed(track);
        log.info("[music] 点播 title={} bytes={}", track.title(), audio.length);
        return "正在播放《" + track.title() + "》，渠道会以语音消息发出；"
                + "你只需回一句简短的话（比如「来啦」），不要再复述歌名列表或歌词。";
    }

    private static String titles(List<MusicLibrary.Track> tracks) {
        return tracks.stream().map(MusicLibrary.Track::title).collect(Collectors.joining("、"));
    }

    private static String turnId(ToolContext context) {
        if (context == null) {
            return null;
        }
        Object value = context.getContext().get(ToolCallTracer.CTX_TURN_ID);
        return value == null ? null : String.valueOf(value);
    }
}
