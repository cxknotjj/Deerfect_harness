package com.dark.javaHarness.tool.external;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdkHttpTransportTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsOrdinaryBody() throws Exception {
        server.createContext("/ok", exchange -> respond(exchange, 200, "hello 世界"));
        HttpTransport.Response response = new JdkHttpTransport().get(baseUrl + "/ok", Map.of(), TIMEOUT);
        assertEquals(200, response.status());
        assertEquals("hello 世界", response.body());
    }

    @Test
    void readsChunkedBody() throws Exception {
        server.createContext("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("part-one ".getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write("part-two".getBytes(StandardCharsets.UTF_8));
            }
        });
        HttpTransport.Response response = new JdkHttpTransport().get(baseUrl + "/chunked", Map.of(), TIMEOUT);
        assertEquals("part-one part-two", response.body());
    }

    @Test
    void capsOversizedBodyInsteadOfBufferingItWhole() throws Exception {
        byte[] payload = new byte[3 * JdkHttpTransport.MAX_BODY_BYTES];
        Arrays.fill(payload, (byte) 'a');
        String body = new String(payload, StandardCharsets.UTF_8);
        server.createContext("/huge", exchange -> respond(exchange, 200, body));
        HttpTransport.Response response = new JdkHttpTransport().get(baseUrl + "/huge", Map.of(), TIMEOUT);
        assertEquals(JdkHttpTransport.MAX_BODY_BYTES,
                response.body().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void keepsStatusForErrorResponses() throws Exception {
        server.createContext("/denied", exchange -> respond(exchange, 403, "forbidden"));
        HttpTransport.Response response = new JdkHttpTransport().get(baseUrl + "/denied", Map.of(), TIMEOUT);
        assertEquals(403, response.status());
        assertEquals("forbidden", response.body());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
