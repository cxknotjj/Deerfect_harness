package com.dark.javaHarness.tool.external;

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
 */
final class JdkHttpTransport implements HttpTransport {

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
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }
}
