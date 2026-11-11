package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.view.KnowledgeSource;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.contract.spi.ReplyPostProcessor;
import com.dark.javaHarness.contract.api.FileArtifactStore;
import com.dark.javaHarness.contract.api.VoiceArtifactStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 回复后处理与出参装饰（自 {@link ChatServiceImpl} 拆出，超长类拆分 2026-11-05）：
 * 回复后处理器链 / 语音 flag / RAG 出处透出。三条装饰都只读本轮执行结果，不改变执行路径。
 */
@Component
class ReplyDecorator {

    /** 回复后处理器扩展点（渠道无关的通用 SPI，按注册顺序依次调用）；无实现时缺位，后处理零行为 */
    private final ObjectProvider<ReplyPostProcessor> replyPostProcessors;

    /** 本轮语音工件库：工具按 turnId 写入，ChatService 只读查一次并透出 voice:<id> flag */
    private final VoiceArtifactStore voiceArtifacts;

    /** 本轮文件工件库：send_file 按 turnId 写入，这里透出 file:<id> flag */
    private final FileArtifactStore fileArtifacts;

    /** 知识检索器（RAG 出处透出源）；bean 缺位（知识库禁用）时 meta.sources 恒空 */
    private final ObjectProvider<KnowledgeRetriever> knowledgeRetrieverProvider;

    ReplyDecorator(ObjectProvider<ReplyPostProcessor> replyPostProcessors,
                   VoiceArtifactStore voiceArtifacts,
                   FileArtifactStore fileArtifacts,
                   ObjectProvider<KnowledgeRetriever> knowledgeRetrieverProvider) {
        this.replyPostProcessors = replyPostProcessors;
        this.voiceArtifacts = voiceArtifacts;
        this.fileArtifacts = fileArtifacts;
        this.knowledgeRetrieverProvider = knowledgeRetrieverProvider;
    }

    /** 后处理结果载体 */
    record Processed(String text, Set<String> flags) {
    }

    /**
     * 应用所有回复后处理器（无实现时原样返回、flags 为空）。
     *
     * <p>处理器异常直接上抛（优化审查 2026-11-03）：实现都在本仓库内，逐个 catch 后继续会把
     * 实现缺陷变成「静默少做一步后处理」——出参与写回都带着未清洗的内容。
     */
    Processed apply(String raw) {
        String text = raw == null ? "" : raw;
        Set<String> flags = new LinkedHashSet<>();
        for (ReplyPostProcessor p : replyPostProcessors.orderedStream().toList()) {
            ReplyPostProcessor.Processed r = p.process(text);
            if (r != null) {
                text = r.text() == null ? "" : r.text();
                if (r.flags() != null) {
                    flags.addAll(r.flags());
                }
            }
        }
        return new Processed(text, Set.copyOf(flags));
    }

    /** 本轮有语音工件时追加 voice:<id>：渠道无关，支持语音的渠道消费，其他渠道忽略。 */
    Set<String> withVoiceFlag(String turnId, Set<String> flags) {
        return voiceArtifacts.findByTurn(turnId)
                .map(artifact -> {
                    Set<String> merged = new LinkedHashSet<>(flags);
                    merged.add("voice:" + artifact.id());
                    return Set.copyOf(merged);
                })
                .orElse(flags);
    }

    /**
     * 本轮有文件工件时追加 file:<id>：与 voice 同一套语义——渠道无关，
     * 认识的渠道（飞书）上传发送，不认识的忽略 flag 照常回文字。
     */
    Set<String> withFileFlag(String turnId, Set<String> flags) {
        return fileArtifacts.findByTurn(turnId)
                .map(artifact -> {
                    Set<String> merged = new LinkedHashSet<>(flags);
                    merged.add("file:" + artifact.id());
                    return Set.copyOf(merged);
                })
                .orElse(flags);
    }

    /** 语音 + 文件：两者互不排斥（一轮可以既用语音说又带附件） */
    Set<String> withArtifactFlags(String turnId, Set<String> flags) {
        return withFileFlag(turnId, withVoiceFlag(turnId, flags));
    }

    /** 本次会话最近一次知识命中的出处（RAG 出处透出；禁用/无命中返回 null，旧客户端兼容） */
    List<KnowledgeSource> recentSources(String sessionId) {
        KnowledgeRetriever retriever = knowledgeRetrieverProvider.getIfAvailable();
        if (retriever == null) {
            return null;
        }
        List<KnowledgeSource> sources = retriever.recentSources(sessionId);
        return sources.isEmpty() ? null : sources;
    }
}
