package com.dark.javaHarness.music;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 音乐点播配置（{@code app.music.*}）。
 *
 * <p>曲库是本地目录而不是在线音源：在线音源要处理版权、鉴权、限流和可用性，
 * 而「放首歌」这个需求用几首自己放的歌就够——目录换内容不用改代码。
 */
@Component
@ConfigurationProperties(prefix = "app.music")
public class MusicProperties {

    /** 总开关：关闭时 play_music 不注册，模型也不会看到这个工具 */
    private boolean enabled = true;

    /**
     * 曲库目录（相对启动工作目录）。容器部署时要挂卷，镜像里不带音频文件——
     * 音乐有版权，不该烘进镜像。
     */
    private String dir = "voice";

    /**
     * 单文件上限（字节）。飞书语音条上限 30MB，这里留余量：
     * 超过 25MB 的歌转码后仍然可能撞上限，不如提前拒绝。
     */
    private long maxBytes = 25L * 1024 * 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }
}
