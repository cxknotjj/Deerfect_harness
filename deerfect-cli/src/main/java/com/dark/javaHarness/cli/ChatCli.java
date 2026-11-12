package com.dark.javaHarness.cli;

import com.dark.javaHarness.cli.api.ChatApiClient;
import com.dark.javaHarness.cli.input.TerminalInput;
import com.dark.javaHarness.cli.render.TerminalRenderer;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * 命令行聊天客户端：独立进程运行，通过 REST 调用主服务（8080）的 /api/chat/stream（SSE 流式）。
 *
 * <p>超长类拆分 2026-11-05：本类只负责启动与依赖组装（输入/输出通道、会话装载、
 * 续跑状态恢复、命令循环）。职责已下沉：
 * <ul>
 *   <li>{@link CommandDispatcher}：斜杠命令解析与分发（/help、/agent、/routing、/resume、/provider）；</li>
 *   <li>{@link StreamRenderer}：SSE 逐帧渲染（进度/token）、回合小结与会话 ID 记忆；</li>
 *   <li>{@link ChatSessionState}：会话/Agent/路径/续跑目标的可变选择状态；</li>
 *   <li>{@link TerminalRenderer}：阶段 spinner、工具调用行与 Markdown 着色；</li>
 *   <li>{@link TerminalInput}：JLine 输入（历史/补全/多行粘贴，无 TTY 时降级行式读取）；</li>
 *   <li>{@link ResumeStateStore}：/resume 目标的跨进程持久化。</li>
 * </ul>
 *
 * 重要：CLI 是纯 HTTP 客户端，不监听任何端口，占用的是你当前的终端进程。
 * 主服务（JavaHarnessApplication）负责监听 8080、保留日志、执行 Agent 编排。
 *
 * 启动方式（另开一个终端，在项目根目录）：
 *   mvn -s .mvn/settings.xml -Pcli compile exec:exec   ← 推荐：独立进程接管终端，历史/补全可用
 *   mvn -q -s .mvn/settings.xml exec:java              ← 可用，但无 TTY，输入体验降级
 *   终端需支持 ANSI 转义与 UTF-8（Windows Terminal / PowerShell 7 推荐）
 */
public class ChatCli {

    private final ChatApiClient api;
    private final String baseUrl;
    private final TerminalRenderer renderer = new TerminalRenderer();

    /** 会话/Agent/路径/续跑目标（命令分发与回合执行共享） */
    private final ChatSessionState state = new ChatSessionState();

    /** /resume 无参续跑目标的持久化存储（sessionId + goalId 两行，跨 CLI 进程保留） */
    private final ResumeStateStore resumeStore = new ResumeStateStore();

    private final StreamRenderer streamRenderer;
    private final CommandDispatcher dispatcher;

    /**
     * 统一 UI 输出通道：JLine 模式下切到 terminal.output()（jansi 宽字符通道 WriteConsoleW，
     * 不受控制台代码页影响）；降级模式为 stdout（main 里已包装 UTF-8）。
     * 所有直接输出（banner/帮助/会话提示）必须走它，禁止直接用 System.out——
     * 否则 UTF-8 字节会被 GBK 代码页终端解读成乱码。
     */
    private PrintStream ui = System.out;

    /** 默认连本机主服务 8080 */
    public ChatCli() {
        this.baseUrl = "http://localhost:8080";
        this.api = new ChatApiClient(baseUrl);
        Supplier<PrintStream> out = () -> ui;
        this.streamRenderer = new StreamRenderer(api, baseUrl, renderer, state, out, this::persistResumeState);
        this.dispatcher = new CommandDispatcher(api, state, streamRenderer, out, this::persistResumeState);
    }

    /** 程序入口：启动交互式聊天循环 */
    public static void main(String[] args) {
        // 中文 Windows 控制台默认 GBK，强制 stdout 走 UTF-8，保证 ✓ 与框线符号不乱码
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),
                true, StandardCharsets.UTF_8));
        new ChatCli().chatLoop();
    }

    /** 交互式循环（独立终端使用） */
    public void chatLoop() {
        // 先定输入源，再定输出通道（JLine 模式下 renderer 与 banner 全走 terminal.output() 防乱码）
        TerminalInput.LineInput input = TerminalInput.open();
        ui = input.out();
        renderer.useOutput(ui);

        ui.println("==============================================");
        ui.println(" javaHarness CLI - 聊天客户端 (主服务: 8080)");
        ui.println(" 直接输入文本对话，/help 帮助，/exit 退出");
        ui.println(" ↑↓ 翻输入历史，Tab 补全命令");
        ui.println("==============================================");

        loadExistingSession();
        restoreResumeState();

        while (true) {
            String line = input.read();
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (dispatcher.dispatch(line)) {
                break;
            }
        }
        input.shutdown();
        ui.println("再见！");
    }

    /** 进入对话前，先调 /api/harness/sessions 取一个已有会话，后续 stream 请求携带其 sessionId 延续上下文 */
    private void loadExistingSession() {
        try {
            String existing = api.firstSessionId();
            if (existing != null && !existing.isBlank()) {
                state.sessionId(existing);
                ui.println("\033[90m已加载会话 " + state.sessionId() + "\033[0m");
            } else {
                ui.println("\033[90m暂无历史会话，将新建会话\033[0m");
            }
        } catch (IOException e) {
            ui.println("获取会话列表失败（将新建会话）: " + e.getMessage());
        }
    }

    /** 把当前 sessionId + goalId 写入持久化文件（失败仅提示，不影响主流程） */
    private void persistResumeState() {
        try {
            resumeStore.save(state.sessionId(), state.lastOrchestratedGoalId());
        } catch (Exception e) {
            ui.println("\033[90m（续跑状态持久化失败: " + e.getMessage() + "）\033[0m");
        }
    }

    /** CLI 启动时恢复持久化的续跑目标：会话匹配且服务端确认未完成才提示（已完成的直接清记录） */
    private void restoreResumeState() {
        if (state.lastOrchestratedGoalId() != null || state.sessionId() == null) {
            return;
        }
        try {
            ResumeStateStore.State resumed = resumeStore.load();
            if (resumed == null) {
                return;
            }
            String stateSessionId = resumed.sessionId();
            String stateGoalId = resumed.goalId();
            if (stateGoalId == null || stateGoalId.isBlank() || !state.sessionId().equals(stateSessionId)) {
                return;
            }
            // 向服务端确认任务未完成：避免「记录里的任务实际已跑完」时误导用户 /resume（撞 409）
            String status = null;
            try {
                status = api.goalStatus(stateGoalId);
            } catch (IOException ignored) {
                // 服务未就绪/网络失败：保留提示（/resume 真被拦截时也会自动清记录）
            }
            if ("SUCCEEDED".equals(status)) {
                state.lastOrchestratedGoalId(null);
                persistResumeState(); // 覆盖持久化文件，彻底清除过期记录
                ui.println("\033[90m上次编排任务已完成（服务端已确认），无需续跑\033[0m");
                return;
            }
            state.lastOrchestratedGoalId(stateGoalId);
            ui.println("\033[90m已恢复可续跑任务 " + stateGoalId + "（/resume 继续）\033[0m");
        } catch (Exception e) {
            ui.println("\033[90m（续跑状态恢复失败: " + e.getMessage() + "）\033[0m");
        }
    }
}
