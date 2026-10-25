package com.dark.javaHarness.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import java.time.Duration;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/**
 * OpenAI 兼容端点客户端构建（Spring AI 2.0 起 OpenAI 接入改用官方 openai-java SDK）：
 * 统一走 Spring AI 的 OkHttp 传输层，显式设置连接/读超时并按需关闭 SDK 隐式重试。
 *
 * <p>与 Spring AI 1.x 的 {@code OpenAiApi} 手工构造语义对齐：
 * <ul>
 *   <li>连接超时：建连阶段上限；
 *   <li>读超时：包间等待上限（流式按 token 间隔判定），非整请求上限；
 *   <li>整请求不设上限（{@code request=ZERO}）：长回答流式可超过读超时；
 *   <li>maxRetries=0：重试统一收敛到业务侧 LlmRetry（每次尝试都有观测记录）。
 * </ul>
 */
public final class OpenAiClients {

    private OpenAiClients() {
    }

    /**
     * @param baseUrl        端点地址（OpenAI 兼容）
     * @param apiKey         服务商密钥
     * @param connectTimeout 建连超时
     * @param readTimeout    包间读超时
     */
    public static OpenAIClient build(String baseUrl, String apiKey,
                                     Duration connectTimeout, Duration readTimeout) {
        SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(Timeout.builder()
                        .connect(connectTimeout)
                        .read(readTimeout)
                        .write(readTimeout)
                        .request(Duration.ZERO)
                        .build())
                .build();
        ClientOptions options = ClientOptions.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .apiKey(apiKey)
                .httpClient(httpClient)
                .maxRetries(0)
                .build();
        return new OpenAIClientImpl(options);
    }

    /**
     * 端点归一化：官方 SDK 的 baseUrl 需包含版本前缀（默认 {@code https://api.openai.com/v1}，
     * 请求再拼 {@code /chat/completions}）；而 model_provider 表的 api_url 沿用 Spring AI 1.x
     * 语义（根地址，1.x 内部拼 {@code /v1/chat/completions}）。此处补齐 {@code /v1}，
     * 已带版本后缀的配置保持原样。
     */
    static String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.endsWith("/v1") ? url : url + "/v1";
    }
}
