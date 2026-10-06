package com.dark.javaHarness.tool;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本净化器（静态纯函数）：工具结果与聚合输入的坏字符 / URL 噪音治理。
 *
 * <p>背景（2026-10-06 聚合思考循环复盘）：tavily 等检索工具返回的网页快照携带编码破损
 * 片段（GBK 内容按 UTF-8 解码产生的 U+FFFD 串、孤立代理对），经工具结果注入专家上下文、
 * 被专家忠实复制进交付物，再原样拼进聚合 user——小模型（qwen3.7-flash，thinking=1）读到
 * 非法 token 序列后思考退化成无意义字符循环，一路烧满输出上限（实测 42s~191s）。
 *
 * <p>三个口径：
 * <ul>
 *   <li>{@link #stripBrokenChars}：剥 U+FFFD / 控制字符（保留 \n \t）/ 未配对代理——
 *       注入边界通用清洗；</li>
 *   <li>{@link #dedupUrls}：URL 去查询参数与锚点（?/# 起全部截除）+ 去重（首次出现保留
 *       origin+path，重复出现收缩为裸 host）；</li>
 *   <li>{@link #forAggregateInput}：聚合输入专用 = stripBrokenChars + dedupUrls + 长度封顶
 *       （防小模型复读的触发源消灭）。</li>
 * </ul>
 */
public final class TextSanitizer {

    /** URL 匹配：排除空白与常见中文标点（避免吞掉句子收尾）；尾部 ASCII 标点另行剥离 */
    private static final Pattern URL = Pattern.compile("https?://[^\\s，。；！？、）】」》\"'<>]+");

    private TextSanitizer() {
    }

    /**
     * 剥离坏字符：U+FFFD 替换符、控制字符（\n \t 保留，\r 直接丢弃）、未配对代理。
     * 配对代理（合法增补平面字符）完整保留。
     */
    public static String stripBrokenChars(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    sb.append(c).append(text.charAt(++i)); // 配对代理完整保留
                } // 未配对高位代理丢弃
            } else if (Character.isLowSurrogate(c) || c == '\uFFFD') {
                // 未配对低位代理 / 替换符丢弃
            } else if (c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7F
                    && Character.getType(c) != Character.CONTROL)) {
                sb.append(c);
            } // 其余控制字符（C0 除 \n\t、DEL、C1）丢弃
        }
        return sb.toString();
    }

    /**
     * URL 去噪：所有 URL 去掉查询参数与锚点（?/# 起截除，尾部 ASCII 标点还原给正文）；
     * 归一化后重复的 URL（origin+path 相同）仅首次保留全文，重复出现收缩为裸 host。
     * 无 URL 的文本原样返回。
     */
    public static String dedupUrls(String text) {
        if (text == null || text.isEmpty() || !text.contains("://")) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        Map<String, String> seen = new HashMap<>(); // 归一化 origin+path（小写）→ host
        Matcher m = URL.matcher(text);
        int last = 0;
        while (m.find()) {
            String url = m.group();
            String core = url.replaceAll("[.,;:!?)\\]}>\"']+$", ""); // 尾部 ASCII 标点还原
            String tail = url.substring(core.length());
            int query = core.indexOf('?');
            int anchor = core.indexOf('#');
            int cut = query >= 0 && anchor >= 0 ? Math.min(query, anchor)
                    : query >= 0 ? query : anchor >= 0 ? anchor : core.length();
            String prefix = core.substring(0, cut);
            String host = prefix.replaceAll("^https?://", "").split("/", 2)[0];
            String key = prefix.toLowerCase();
            String replacement = seen.putIfAbsent(key, host) == null ? prefix : host;
            sb.append(text, last, m.start()).append(replacement).append(tail);
            last = m.end();
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    /**
     * 聚合输入净化：stripBrokenChars → dedupUrls → 长度封顶（超长截断带标记）。
     * maxChars ≤ 0 = 不限长。
     */
    public static String forAggregateInput(String text, int maxChars) {
        String cleaned = dedupUrls(stripBrokenChars(text));
        if (cleaned == null || maxChars <= 0 || cleaned.length() <= maxChars) {
            return cleaned;
        }
        // 截断后过一遍坏字符剥离（防截断点劈开代理对）
        return stripBrokenChars(cleaned.substring(0, maxChars)) + "…[单条子任务结果超长已截断]";
    }
}
