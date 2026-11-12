package com.dark.javaHarness.validation;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 模型供应商录入规则（无状态纯函数）：provider / apiUrl 必填，
 * models 非空且不含空项，模型名仅允许字母/数字/点/下划线/连字符。
 */
public final class ProviderRules {

    /** 模型名白名单：方括号、反引号等符号多为 CLI 误输入（如 markdown 包裹残留） */
    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private ProviderRules() {
    }

    /**
     * 校验供应商录入参数。
     *
     * @throws IllegalArgumentException 任一字段非法（文案与 CLI 错误提示口径一致）
     */
    public static void validateProvider(String provider, String apiUrl, List<String> models) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider 不能为空");
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalArgumentException("apiUrl 不能为空");
        }
        if (models == null || models.isEmpty()
                || models.stream().anyMatch(m -> m == null || m.isBlank())) {
            throw new IllegalArgumentException("models 不能为空且不能含空项");
        }
        for (String model : models) {
            String trimmed = model.trim();
            if (!MODEL_NAME.matcher(trimmed).matches()) {
                throw new IllegalArgumentException(
                        "模型名 '" + trimmed + "' 含非法字符（仅允许字母/数字/./_/-）");
            }
        }
    }
}
