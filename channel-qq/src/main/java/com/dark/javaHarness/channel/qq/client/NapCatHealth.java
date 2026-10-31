package com.dark.javaHarness.channel.qq.client;

import com.dark.javaHarness.channel.ChannelHealth;
import com.dark.javaHarness.channel.qq.dto.ApiResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * NapCat 渠道健康实现：把「渠道开关 + get_login_info 探活」暴露给 core 监控页。
 *
 * <p>探活结果缓存 30s：监控页 15s 刷新时不会每次请求都打 NapCat；连续不可达时最多
 * 每 30s 付出一次 HTTP 调用代价。NapCat 客户端仅在 napcat.enabled=true 时装配，
 * 故用 ObjectProvider 软依赖，关闭渠道时只报告 enabled=false。
 */
@Component
public class NapCatHealth implements ChannelHealth {

    private static final long PROBE_TTL_MS = 30_000;

    private final NapCatProperties props;
    private final ObjectProvider<NapCatApiClient> clientProvider;

    private volatile long lastProbeAt;
    private volatile boolean lastOk;
    private volatile String lastDetail = "尚未探测";

    public NapCatHealth(NapCatProperties props, ObjectProvider<NapCatApiClient> clientProvider) {
        this.props = props;
        this.clientProvider = clientProvider;
    }

    @Override
    public String channelName() {
        return "NapCat / QQ";
    }

    @Override
    public Snapshot snapshot() {
        if (!props.isEnabled()) {
            return new Snapshot(false, false, "napcat.enabled=false，QQ 渠道未装配");
        }
        NapCatApiClient client = clientProvider.getIfAvailable();
        if (client == null) {
            return new Snapshot(true, false, "渠道已启用但 NapCat 客户端未装配");
        }
        long now = System.currentTimeMillis();
        if (now - lastProbeAt >= PROBE_TTL_MS) {
            ApiResult result = client.getLoginInfo();
            lastOk = result.retcode() == 0;
            lastDetail = lastOk
                    ? "可达（" + props.getApiBaseUrl() + "）"
                    : "不可达（retcode=" + result.retcode() + "，检查 NapCat/token）";
            lastProbeAt = now;
        }
        return new Snapshot(true, lastOk, lastDetail);
    }
}
