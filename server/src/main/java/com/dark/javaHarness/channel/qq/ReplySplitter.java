package com.dark.javaHarness.channel.qq;

import java.util.ArrayList;
import java.util.List;

/**
 * 回复分段纯函数集（自 {@link OneBotEventServiceImpl} 拆出，超长类拆分 2026-09-25）：
 * 渐进/超长两种策略的文本切分，无状态零依赖，便于单测直调。
 */
final class ReplySplitter {

    private ReplySplitter() {
    }

    /**
     * 超长块层级下切（参考文档切片逻辑）：先按单换行拆行，行仍超长再按句末标点
     * （。！？!?；;）拆句，标点保留在句尾。<b>不设字符硬切兜底</b>——单个无标点块
     * （长 URL、代码段等）仍超长时原样成片，直接发送。
     * 返回的每片都 ≤ max，除非该片本身不可再分。
     */
    static List<String> splitOversizedBlock(String block, int max) {
        if (max <= 0 || block.length() <= max) {
            return List.of(block);
        }
        List<String> leaves = new ArrayList<>();
        for (String line : block.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() <= max) {
                leaves.add(line);
            } else {
                leaves.addAll(splitSentences(line));
            }
        }
        return leaves.isEmpty() ? List.of(block) : leaves;
    }

    /** 句级切片：句末标点后断句，标点保留在前句尾部；单句不再细分 */
    private static List<String> splitSentences(String line) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            current.append(c);
            if ("。！？!?；;".indexOf(c) >= 0) {
                sentences.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            sentences.add(current.toString());
        }
        return sentences;
    }

    /**
     * 渐进模式拆分：与 {@link #splitReply} 的差异——不设总长门槛，多段回答**始终按空行段落
     * 逐条发送**（像人连发）；单段超长经 {@link #splitOversizedBlock} 层级下切（行 → 句，
     * 无字符硬切）；段落数超过 maxChunks 时尾部段落合并为最后一条（maxChunks &lt;= 0 = 不限制；
     * 合并条可能超过 max，可接受——仅极端长回答触发）。单段回答返回单条，行为与关闭渐进一致。
     */
    static List<String> splitProgressive(String text, int max, int maxChunks) {
        String trimmed = text == null ? "" : text.trim();
        List<String> paragraphs = new ArrayList<>();
        for (String p : trimmed.split("\n\n", -1)) {
            if (p.isEmpty()) {
                continue;
            }
            if (max > 0 && p.length() > max) {
                paragraphs.addAll(splitOversizedBlock(p, max));
            } else {
                paragraphs.add(p);
            }
        }
        if (paragraphs.size() <= 1) {
            return List.of(trimmed);
        }
        if (maxChunks > 0 && paragraphs.size() > maxChunks) {
            List<String> capped = new ArrayList<>(paragraphs.subList(0, maxChunks - 1));
            capped.add(String.join("\n\n", paragraphs.subList(maxChunks - 1, paragraphs.size())));
            return capped;
        }
        return paragraphs;
    }

    /**
     * 超长分段：优先按空行段落边界打包（段间距保留在段内），单段仍超长再按字符硬切。
     * max &lt;= 0 表示不限制（整段一条发送）。
     */
    static List<String> splitReply(String text, int max) {
        String trimmed = text == null ? "" : text.trim();
        if (max <= 0 || trimmed.length() <= max) {
            return List.of(trimmed);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : trimmed.split("\n\n", -1)) {
            String p = paragraph;
            if (max > 0 && p.length() > max) {
                // 超长段层级下切（行 → 句），叶子贪心打包（\n 连接）；切不动的整句原样成片
                for (String leaf : splitOversizedBlock(p, max)) {
                    if (!current.isEmpty() && current.length() + 1 + leaf.length() > max) {
                        parts.add(current.toString());
                        current.setLength(0);
                    }
                    if (!current.isEmpty()) {
                        current.append('\n');
                    }
                    current.append(leaf);
                }
                continue;
            }
            if (p.isEmpty()) {
                continue;
            }
            if (current.isEmpty()) {
                current.append(p);
            } else if (current.length() + 2 + p.length() <= max) {
                current.append("\n\n").append(p);
            } else {
                parts.add(current.toString());
                current.setLength(0);
                current.append(p);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts.isEmpty() ? List.of(trimmed) : parts;
    }
}
