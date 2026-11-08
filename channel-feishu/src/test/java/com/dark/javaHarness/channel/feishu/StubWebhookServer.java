package com.dark.javaHarness.channel.feishu;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地 webhook 桩：用 JDK 自带 HttpServer 顶替飞书，验证请求体与错误分流。
 *
 * <p>为什么不用 mock {@link HttpClient}：真正要验的是「发出去的 JSON 长什么样」与
 * 「响应码怎么翻译」，mock 客户端就把这两件事一起 mock 掉了。本地起一个端口反而更接近真实。
 */
final class StubWebhookServer implements AutoCloseable {

    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"code\":0,\"msg\":\"success\"}";

    StubWebhookServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    void respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    List<String> requests() {
        return requests;
    }

    /** 不走系统代理：本机回环被代理接管时测试会莫名超时 */
    static HttpClient httpClient() {
        ProxySelector noProxy = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e) {
                // 直连没有代理可失败
            }
        };
        return HttpClient.newBuilder().proxy(noProxy).build();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
