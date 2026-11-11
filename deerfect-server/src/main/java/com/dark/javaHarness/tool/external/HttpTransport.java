package com.dark.javaHarness.tool.external;

import java.time.Duration;
import java.util.Map;

/**
 * 出站 HTTP 的最小抽象：抽成函数式接口是为了让工具逻辑可单测——
 * 断言参数白名单、鉴权头、响应整形都不该依赖真实网络（与 SendFileTool.Shell 同一思路）。
 */
@FunctionalInterface
public interface HttpTransport {

    Response get(String url, Map<String, String> headers, Duration timeout) throws Exception;

    record Response(int status, String body) {
    }
}
