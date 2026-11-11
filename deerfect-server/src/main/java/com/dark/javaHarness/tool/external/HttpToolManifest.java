package com.dark.javaHarness.tool.external;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 声明式 HTTP 工具的清单模型（{@code tools/http/*.yaml} 的内存形态）。
 *
 * <p>一个清单 = 一个工具 + 若干「资源」。资源是给人看的名字（stock / product / out_order），
 * 每个资源绑定一条固定路径和一组允许透传的参数——模型只能选资源与参数值，
 * 不能拼路径，因此清单文件本身是唯一可信的端点来源。
 *
 * <p>为什么不用「一条清单一个端点」：WMS 这类系统有二十个只读端点，
 * 逐个做成工具会让工具面里塞满近义名字（模型选择准确率随数量下降）。
 * 收敛成一个工具 + 一份 skill 说明书，端点知识放在数据里而不是 prompt 里。
 */
public record HttpToolManifest(
        String pack,
        Path file,
        String baseUrl,
        String baseUrlHint,
        Auth auth,
        int timeoutMs,
        ResponseShape response,
        ToolSpec tool,
        Map<String, Resource> resources) {

    /** 鉴权：头名 + 取值的环境变量名（凭据不落配置文件） */
    public record Auth(String header, String tokenEnv) {

        public boolean present() {
            return header != null && !header.isBlank();
        }
    }

    /**
     * 响应整形。
     *
     * @param format     raw=原样返回；jeecg=拆 {@code result} 包装（records 分页 / 数组 / 单对象三种形态）
     * @param dropFields 逐行剔除的字段（jeecg 审计字段对模型无价值，还会撑爆 token）
     * @param maxChars   输出字符上限，超出截断并标注
     */
    public record ResponseShape(String format, List<String> dropFields, int maxChars) {

        public boolean jeecg() {
            return "jeecg".equalsIgnoreCase(format);
        }
    }

    /** 模型可见的工具签名 */
    public record ToolSpec(String name, String description, String resourceParam, String paramsParam) {
    }

    /**
     * 一个资源。
     *
     * @param fixed 固定参数（如 jeecg 的 taskType 枚举）：不暴露给模型，服务端无条件附加
     */
    public record Resource(String path, List<String> params, List<String> requires,
                           Map<String, String> fixed, String summary) {
    }

    /** 缺 base-url 时工具仍注册（模型看得见、调用时得到引导），报告里标 missing-config */
    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /** 该工具调用需要的环境变量名（未配置时给模型/运维一条可执行的提示） */
    public String requiredEnvHint() {
        String hint = baseUrlHint == null ? "" : baseUrlHint.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\$\\{([A-Za-z0-9_.]+)(?::[^}]*)?}").matcher(hint);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return hint.isBlank() ? "base-url" : hint;
    }
}
