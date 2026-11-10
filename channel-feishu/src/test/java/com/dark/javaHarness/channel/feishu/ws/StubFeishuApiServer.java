package com.dark.javaHarness.channel.feishu.ws;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地飞书 API 桩：只实现 token 与发消息两个端点，记录请求原文供断言。
 *
 * <p>用真实 HTTP 而不是 mock {@code HttpClient}：这个类要验的就是「URL 上的
 * receive_id_type 对不对、content 是否二次序列化」，mock 客户端会把它们一起抹掉。
 */
final class StubFeishuApiServer implements AutoCloseable {

    private final HttpServer server;
    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<String> messageRequests = new CopyOnWriteArrayList<>();

    private volatile String tokenBody = "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"t-test\",\"expire\":7200}";
    private volatile String messageBody = "{\"code\":0,\"msg\":\"success\",\"data\":{\"message_id\":\"om_test\"}}";
    private volatile int messageStatus = 200;
    private volatile byte[] imageBytes = {1, 2, 3};
    private volatile String imageContentType = "image/png";
    private volatile int imageStatus = 200;
    private final List<String> resourceRequests = new CopyOnWriteArrayList<>();

    StubFeishuApiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", exchange -> {
            tokenRequests.add(read(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, tokenBody);
        });
        server.createContext("/open-apis/im/v1/messages", exchange -> {
            messageRequests.add(exchange.getRequestURI().getQuery() + "|" + read(exchange.getRequestBody().readAllBytes()));
            respond(exchange, messageStatus, messageBody);
        });
        // 更长前缀优先：/messages/{id}/resources/{key} 落到这里，发消息仍走上面那个
        server.createContext("/open-apis/im/v1/messages/", exchange -> {
            resourceRequests.add(exchange.getRequestURI().getQuery() + "|" + exchange.getRequestURI().getPath());
            respondBytes(exchange, imageStatus, imageBytes, imageContentType);
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respondMessage(int status, String body) {
        this.messageStatus = status;
        this.messageBody = body;
    }

    void respondToken(String body) {
        this.tokenBody = body;
    }

    List<String> tokenRequests() {
        return tokenRequests;
    }

    List<String> messageRequests() {
        return messageRequests;
    }

    void respondImage(int status, byte[] data, String contentType) {
        this.imageStatus = status;
        this.imageBytes = data;
        this.imageContentType = contentType;
    }

    List<String> resourceRequests() {
        return resourceRequests;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String read(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 二进制响应：图片下载要看 Content-Type 决定 mimeType，不能用 JSON 那个方法 */
    private static void respondBytes(com.sun.net.httpserver.HttpExchange exchange, int status,
                                     byte[] bytes, String contentType) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
