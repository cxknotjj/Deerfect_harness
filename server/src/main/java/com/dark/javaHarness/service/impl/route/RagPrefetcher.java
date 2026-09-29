package com.dark.javaHarness.service.impl.route;

import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG 入口预取器（自 {@link ChatServiceImpl} 拆出，超长类拆分 2026-09-25）：
 * 进程级共享预取池（守护线程、core 空闲 60s 回收，饱和静默丢弃——预取是纯加速，
 * 被丢的请求退化为组装期现查）+ 会话绑定知识库解析 + 预取提交与汇合等待。
 * 提交签名显式化（agentName, sessionId, query）：会话 agent 名解析留守宿主（入口解析后传入），
 * kb 绑定解析内聚本类（agentService 查 config → parseBinding，未绑定不入池）；
 * 以 bean 注册于 KnowledgeConfig（随 knowledgeRetriever 同条件装配，知识库禁用时缺位），
 * 供入口与编排侧复用同一共享池。
 * judge 与预取的汇合次序（并行后汇合）属聊天编排语义，仍由宿主 {@code resolveAgentWithPrefetch} 编排。
 */
public final class RagPrefetcher {

    private static final Logger log = LoggerFactory.getLogger(RagPrefetcher.class);

    /** 预取汇合窗口（秒）：预取内部已被 app.knowledge.search-timeout-seconds 限时，本值仅为快速 judge 场景的等待上限 */
    private static final long PREFETCH_GRACE_SECONDS = 2;

    private final KnowledgeRetriever knowledgeRetriever;
    private final com.dark.javaHarness.service.AgentService agentService;
    private final ThreadPoolExecutor pool;

    public RagPrefetcher(KnowledgeRetriever knowledgeRetriever,
                  com.dark.javaHarness.service.AgentService agentService) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.agentService = agentService;
        this.pool = new ThreadPoolExecutor(
                2, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "rag-prefetch");
                    t.setDaemon(true);
                    return t;
                },
                // 饱和静默丢弃:预取是纯加速,被丢的请求退化为组装期现查(与汇合超时同语义)
                new ThreadPoolExecutor.DiscardPolicy());
        this.pool.allowCoreThreadTimeOut(true);
    }

    /**
     * 入口预取提交（显式签名）：kb 绑定解析内聚——agent 名为 null/空白或未绑定知识库
     * （config 缺失 / parseBinding 为 null）不入池无事发生；任何失败静默返回 null
     * （预取是纯加速，不影响主流程）
     */
    public Future<?> submit(String agentName, String sessionId, String query) {
        try {
            List<String> kbs = resolveKbs(agentName);
            if (kbs == null) {
                return null; // 未绑定知识库（或 agent 名缺失），无事发生
            }
            return pool.submit(() -> doPrefetch(agentName, sessionId, query, kbs));
        } catch (Exception e) {
            log.debug("[chat] RAG 预取提交失败（静默）：{}", safeMessage(e));
            return null;
        }
    }

    /** 汇合预取：judge 完成后小幅等待；超时取消放弃（预取是纯加速，失败退化为组装期现查） */
    public void await(Future<?> prefetch) {
        if (prefetch == null) {
            return;
        }
        try {
            prefetch.get(PREFETCH_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            prefetch.cancel(true);
            log.debug("[chat] RAG 预取未在汇合窗口内完成，放弃");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            prefetch.cancel(true);
        } catch (Exception e) {
            log.debug("[chat] RAG 预取失败（静默）：{}", safeMessage(e));
        }
    }

    /** kb 绑定解析（内聚）：agentService 查 config → parseBinding；agent 名缺失/未绑定返回 null（异常上抛由 submit 静默兜底） */
    private List<String> resolveKbs(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return null;
        }
        com.dark.javaHarness.domain.AgentConfig config =
                agentService.getAgentConfig(agentName).orElse(null);
        return KnowledgeRetriever.parseBinding(config == null ? null : config.knowledge());
    }

    /** 入口预取：按已解析的知识库绑定发起预取（任何失败静默，不影响主流程） */
    private void doPrefetch(String agentName, String sessionId, String query, List<String> kbs) {
        try {
            knowledgeRetriever.prefetch(agentName, sessionId, query, kbs);
        } catch (Exception e) {
            log.debug("[chat] RAG 预取跳过（静默）：{}", safeMessage(e));
        }
    }

    private static String safeMessage(Throwable ex) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return msg.replaceAll("[\\r\\n]+", " ");
    }
}
