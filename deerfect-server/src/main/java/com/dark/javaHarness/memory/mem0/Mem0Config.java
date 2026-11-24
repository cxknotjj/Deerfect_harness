package com.dark.javaHarness.memory.mem0;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * mem0 插件装配：仅在 {@code app.memory.mem0.enabled=true} 时创建客户端。
 *
 * <p>连接/读超时在这里落到 RestClient 的请求工厂；关闭插件时不产生任何外部客户端 Bean。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.memory.mem0", name = "enabled", havingValue = "true")
public class Mem0Config {

    @Bean
    public Mem0Client mem0Client(Mem0Properties properties, RestClient.Builder builder, MeterRegistry meters) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        RestClient restClient = builder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-API-Key", properties.getApiKey())
                .build();
        return new Mem0Client(restClient, meters);
    }
}
