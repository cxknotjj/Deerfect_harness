package com.dark.javaHarness.tool.external;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 默认传输：JDK HttpClient。
 *
 * <p>不用 Spring RestClient 是为了让这个包不依赖容器（工具加载器在单测里直接 new 即可）；
 * 连接超时固定 5 秒、读超时按清单配置，避免外部系统挂住回复线程。
 *
 * <p>响应体限读：展示层按清单 maxChars 截断发生在整包读入之后，超大响应会先在内存里
 * 占满再被丢弃；这里用流式读取封顶 {@link #MAX_BODY_BYTES}，只给模型留可截断的前缀。
 */
final class JdkHttpTransport implements HttpTransport {

    /** 读取上限（字节）：超过部分不再读入内存，展示层仍按 maxChars 二次截断 */
    static final int MAX_BODY_BYTES = 1 << 20;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public Response get(String url, Map<String, String> headers, Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET();
        headers.forEach(builder::header);
        HttpResponse<InputStream> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream stream = response.body()) {
            body = stream.readNBytes(MAX_BODY_BYTES);
        }
        return new Response(response.statusCode(), new String(body, StandardCharsets.UTF_8));
    }
}
