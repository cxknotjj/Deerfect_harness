package com.dark.javaHarness.channel.feishu.ws;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 飞书长连接的生命周期：何时启动、何时停止、停不下来怎么办。
 *
 * <p><b>为什么停止要强制结束 JVM</b>：oapi-sdk 2.4.19 的 {@code Client} 没有公开 stop，
 * 内部 {@code pingLoop} 是 {@code while(true)} 且吞掉中断，OkHttp 线程也不是守护线程——
 * JVM 永远不会自然退出。{@link #stop()} 在断开并等 3 秒后调用 {@code Runtime.halt(0)}；
 * 此时 Spring 的优雅停机（Tomcat 排空、数据源关闭）已经执行到最后一个 lifecycle 阶段，
 * 强退只影响这几个无法停止的 SDK 线程。升级 SDK 后请重跑「启动 → Ctrl+C」确认行为。
 *
 * <p>phase 取 {@link Integer#MIN_VALUE}：Spring 按 phase 降序停止，这个值保证本组件最后停，
 * 反过来会在业务线程还在跑时强退。
 */
public class FeishuWsClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FeishuWsClient.class);

    /** 强退前的缓冲：留给 @PreDestroy 与日志刷盘 */
    private static final long HALT_DELAY_MS = 3000;

    private final FeishuProperties props;
    private final FeishuWsTransport transport;

    private volatile boolean running;

    public FeishuWsClient(FeishuProperties props, FeishuWsTransport transport) {
        this.props = props;
        this.transport = transport;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        transport.start();
        running = true;
        int whitelist = props.getWs().getAllowedUsers() == null ? 0 : props.getWs().getAllowedUsers().size();
        log.info("[feishu] 长连接已启动 appId={} 白名单 {} 个{}（事件经 SDK 回调，处理在本地队列）",
                props.getWs().getAppId(), whitelist, whitelist == 0 ? "，当前为配对模式" : "");
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        transport.stop();
        if (System.getProperty("surefire.test.class.path") != null) {
            // 测试 JVM 不能 halt，否则整个 surefire fork 直接消失
            log.warn("[feishu] 测试环境：跳过强制结束进程（SDK 线程会残留到 fork 退出）");
            return;
        }
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(HALT_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.info("[feishu] 强制结束 JVM：SDK 的 ping/OkHttp 线程无法停止，继续等待会挂住停机");
            Runtime.getRuntime().halt(0);
        }, "feishu-ws-exit");
        exit.setDaemon(true);
        exit.start();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
