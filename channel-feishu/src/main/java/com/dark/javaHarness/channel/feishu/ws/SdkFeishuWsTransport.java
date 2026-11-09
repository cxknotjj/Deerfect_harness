package com.dark.javaHarness.channel.feishu.ws;

import com.dark.javaHarness.channel.feishu.FeishuProperties;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书官方 SDK 的长连接实现：建 WS、把事件转给 {@link FeishuInboundService}。
 *
 * <p>SDK 的两点行为决定了这里只能这样写：
 * <ul>
 *   <li>{@code Client.start()} 连接后立刻返回，收消息在 SDK 自己的线程里；</li>
 *   <li>没有公开 stop，{@code disconnect()} 是 protected——只能反射调用。</li>
 * </ul>
 * 所以「停机后 SDK 线程停不下来」的收尾放在 {@link FeishuWsClient}，不在这里。
 */
public class SdkFeishuWsTransport implements FeishuWsTransport {

    private static final Logger log = LoggerFactory.getLogger(SdkFeishuWsTransport.class);

    private final FeishuProperties props;
    private final FeishuInboundService inbound;

    private volatile Client client;
    private volatile boolean started;

    public SdkFeishuWsTransport(FeishuProperties props, FeishuInboundService inbound) {
        this.props = props;
        this.inbound = inbound;
    }

    @Override
    public void start() {
        if (started) {
            return;
        }
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", nullToEmpty(props.getWs().getEncryptKey()))
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        // 必须在 3 秒内返回：这里只入队，不调模型
                        inbound.onEvent(event);
                    }
                })
                .build();
        Client ws = new Client.Builder(props.getWs().getAppId(), props.getWs().getAppSecret())
                .eventHandler(dispatcher)
                .build();
        this.client = ws;
        Thread thread = new Thread(() -> {
            try {
                ws.start();
            } catch (Throwable t) {
                // 断线重连由 SDK 自己做；这里只保证异常不会静默消失
                log.error("[feishu] 长连接异常退出（SDK 会自动重连）", t);
            }
        }, "feishu-ws");
        thread.setDaemon(true);
        thread.start();
        started = true;
    }

    @Override
    public void stop() {
        Client current = client;
        started = false;
        client = null;
        if (current == null) {
            return;
        }
        try {
            Method disconnect = Client.class.getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(current);
        } catch (ReflectiveOperationException e) {
            log.debug("[feishu] 断开长连接失败（忽略）：{}", e.toString());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
