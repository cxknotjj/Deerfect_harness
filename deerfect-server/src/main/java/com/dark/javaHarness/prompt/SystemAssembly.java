package com.dark.javaHarness.prompt;

import java.util.List;

/**
 * system prompt 装配结果（上下文构成快照的输入）：
 * text 为最终 system 文本（各段以空行拼接 + 可选的 RAG 知识段），
 * sections 为逐段渲染明细，ragTokens 为 RAG 知识块的估算 token（无命中为 0）。
 */
public record SystemAssembly(String text,
                             List<PromptAssembler.RenderedSection> sections,
                             int ragTokens) {
}
