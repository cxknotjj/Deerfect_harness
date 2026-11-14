package com.dark.javaHarness.cli;

import com.dark.javaHarness.cli.api.ChatApiClient;
import com.dark.javaHarness.view.Schedule;
import com.dark.javaHarness.view.GoalView;
import com.dark.javaHarness.view.MemoryView;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 斜杠命令解析与分发（自 {@link ChatCli} 拆出，超长类拆分 2026-11-05）：
 * /help、/new、/agent、/routing、/resume、/provider、/schedule、/goal、
 * /remember、/memories、/forget 与普通消息入口。
 * 回合执行委托给 {@link StreamRenderer}，会话选择状态存放在 {@link ChatSessionState}。
 */
final class CommandDispatcher {

    private final ChatApiClient api;
    private final ChatSessionState state;
    private final StreamRenderer streamRenderer;
    private final Supplier<PrintStream> ui;

    /** 会话切换等场景清空续跑目标后回写持久化文件（由 ChatCli 提供） */
    private final Runnable persistResumeState;

    CommandDispatcher(ChatApiClient api, ChatSessionState state, StreamRenderer streamRenderer,
                      Supplier<PrintStream> ui, Runnable persistResumeState) {
        this.api = api;
        this.state = state;
        this.streamRenderer = streamRenderer;
        this.ui = ui;
        this.persistResumeState = persistResumeState;
    }

    /** 命令分发：返回 true 表示退出 */
    boolean dispatch(String line) {
        if ("/exit".equals(line) || "/quit".equals(line)) {
            return true;
        }
        if ("/help".equals(line)) {
            printHelp();
        } else if ("/new".equals(line)) {
            handleNewSession();
        } else if (line.startsWith("/agent")) {
            handleAgentCommand(line);
        } else if (line.startsWith("/routing")) {
            handleRoutingCommand(line);
        } else if (line.startsWith("/resume")) {
            handleResumeCommand(line);
        } else if (line.startsWith("/provider")) {
            handleProviderCommand(line);
        } else if (line.startsWith("/schedule")) {
            handleScheduleCommand(line);
        } else if (line.startsWith("/goal")) {
            handleGoalCommand(line);
        } else if (line.startsWith("/remember")) {
            handleRememberCommand(line);
        } else if (line.startsWith("/memories")) {
            handleMemoriesCommand(line);
        } else if (line.startsWith("/forget")) {
            handleForgetCommand(line);
        } else {
            streamRenderer.send(line);
        }
        return false;
    }

    /** /remember：把一件事写进显式记忆（服务端落库，跨会话注入） */
    private void handleRememberCommand(String line) {
        String content = line.substring("/remember".length()).strip();
        if (content.isEmpty()) {
            ui.get().println("用法: /remember <要记住的事>");
            ui.get().println("  例: /remember 我的车牌是沪A12345");
            return;
        }
        try {
            MemoryView memory = api.rememberMemory(content);
            ui.get().println("\033[32m✓ 已记住（id=" + memory.id() + "）：" + memory.content() + "\033[0m");
        } catch (IOException e) {
            ui.get().println("记住失败: " + e.getMessage());
        }
    }

    /** /memories：按状态查看记忆（active 默认 / expired / deleted） */
    private void handleMemoriesCommand(String line) {
        String arg = line.substring("/memories".length()).strip().toLowerCase(Locale.ROOT);
        String status = arg.isEmpty() ? "active" : arg;
        if (!List.of("active", "expired", "deleted").contains(status)) {
            ui.get().println("用法: /memories [active|expired|deleted]");
            return;
        }
        try {
            List<MemoryView> items = api.listMemories(status);
            if (items.isEmpty()) {
                ui.get().println("（没有 " + status + " 状态的记忆）");
                return;
            }
            ui.get().println("共 " + items.size() + " 条（" + status + "）：");
            for (MemoryView memory : items) {
                String key = memory.key() == null ? "" : "[" + memory.key() + "] ";
                String expiry = memory.expiresAt() == null ? "长期" : "至 " + memory.expiresAt();
                ui.get().println("  id=" + memory.id() + " " + key + memory.content()
                        + "（来源=" + memory.source() + "，" + memory.createdAt()
                        + "，" + expiry + "，状态=" + memory.status() + "）");
            }
        } catch (IOException e) {
            ui.get().println("查询失败: " + e.getMessage());
        }
    }

    /** /forget：按 id 软删一条记忆（保留审计，不再注入） */
    private void handleForgetCommand(String line) {
        String arg = line.substring("/forget".length()).strip();
        if (arg.isEmpty()) {
            ui.get().println("用法: /forget <id>（先用 /memories 查看 id）");
            return;
        }
        long id;
        try {
            id = Long.parseLong(arg);
        } catch (NumberFormatException e) {
            ui.get().println("id 必须是数字（先用 /memories 查看 id）");
            return;
        }
        try {
            api.forgetMemory(id);
            ui.get().println("\033[32m✓ 已忘掉 id=" + id + "\033[0m");
        } catch (IOException e) {
            ui.get().println("忘掉失败: " + e.getMessage());
        }
    }

    /** 帮助信息（命令名青色着色） */
    private void printHelp() {
        String c = "\033[36m";
        String r = "\033[0m";
        ui.get().println("  直接输入文本与 AI 聊天（默认由服务端智能分流：Agentic 路径 / Graph 编排路径）");
        ui.get().println("  " + c + "/new" + r + "         新建会话（后续对话使用新上下文，旧会话保留）");
        ui.get().println("  " + c + "/agent <id>" + r + "  切换到指定 Agent（agent 表主键，并同步当前会话的 agent_id）");
        ui.get().println("  " + c + "/agent off" + r + "   取消指定，恢复服务端自动分流");
        ui.get().println("  " + c + "/agent" + r + "       查看当前 Agent");
        ui.get().println("  " + c + "/routing" + r + "       查看/设置执行路径：auto | agentic | graph");
        ui.get().println("  " + c + "/resume" + r + "       断点续跑：恢复当前会话最近一次编排任务（从中断处继续）");
        ui.get().println("  " + c + "/resume <goalId>" + r + "  断点续跑指定的 goal（goalId 见每回合末尾的会话信息）");
        ui.get().println("  " + c + "/provider" + r + "      查看模型-服务商映射（llm_provider 表全量）");
        ui.get().println("  " + c + "/provider add" + r + "  新增供应商：/provider add <provider> <apiUrl> <模型1,模型2,...>");
        ui.get().println("  " + c + "/schedule" + r + "     定时任务：list（默认）| add \"<cron>\" <agent> <目标> | del <id>");
        ui.get().println("  " + c + "/goal" + r + "         长期目标：查看 | /goal <目标描述> 登记 | /goal done <id> 完成");
        ui.get().println("  " + c + "/remember <内容>" + r + " 记住一件事（跨会话，之后每轮自动带上）");
        ui.get().println("  " + c + "/memories" + r + "     查看记忆：active（默认）| expired | deleted");
        ui.get().println("  " + c + "/forget <id>" + r + "   忘掉指定 id 的记忆（软删，保留审计）");
        ui.get().println("  " + c + "/exit" + r + "        退出");
    }

    /** 处理 /new 命令：调用服务端新建空会话并切换当前会话（旧会话保留，可随时通过会话列表找回） */
    private void handleNewSession() {
        try {
            String newId = api.createSession();
            state.sessionId(newId);
            // 会话切换后「当前会话的任务」语义随之清空（含持久化文件）
            state.lastOrchestratedGoalId(null);
            persistResumeState.run();
            ui.get().println("\033[90m已开启新会话 " + newId + "\033[0m");
        } catch (IOException e) {
            ui.get().println("新建会话失败: " + e.getMessage());
        }
    }

    /** 处理 /agent 命令：切换、查看或取消（off）当前 Agent */
    private void handleAgentCommand(String line) {
        String arg = line.substring("/agent".length()).trim();
        if (arg.isEmpty()) {
            ui.get().println("当前 Agent: " + (state.agentId() == null
                    ? "自动分流（简单→会话绑定 Agent，复杂→multi-agent）" : state.agentId()));
            return;
        }
        if ("off".equalsIgnoreCase(arg)) {
            state.agentId(null);
            ui.get().println("已恢复服务端自动分流");
            return;
        }
        try {
            long id = Long.parseLong(arg);
            // 先落库（服务端校验 agentId 存在性并更新 session 表 agent_id），成功后才更新内存选择。
            // 尚无会话（首轮未发消息）时仅记内存：下一轮服务端自动建档，请求携带的 agentId 会同步进会话表。
            if (state.sessionId() != null) {
                try {
                    api.switchSessionAgent(state.sessionId(), id);
                } catch (IOException e) {
                    ui.get().println("切换失败（会话 " + state.sessionId() + " 未变更）: " + e.getMessage());
                    return;
                }
            }
            state.agentId(id);
            ui.get().println("已切换到 Agent #" + state.agentId()
                    + (state.sessionId() == null ? "（新会话建档时将登记该 Agent）"
                    : "（会话 " + state.sessionId() + " 已同步）")
                    + "；简单问题由该 Agent 直答，复杂问题仍自动编排；/agent off 可恢复");
        } catch (NumberFormatException e) {
            ui.get().println("agent 编号无效，用法: /agent <数字Id> | /agent off | /agent");
        }
    }

    /** 处理 /routing 命令：查看或设置显式执行路径（auto/agentic/graph）。 */
    private void handleRoutingCommand(String line) {
        String arg = line.substring("/routing".length()).trim().toLowerCase(Locale.ROOT);
        if (arg.isEmpty()) {
            ui.get().println("当前执行路径: " + (state.routing() == null ? "auto（服务端判定）" : state.routing()));
            return;
        }
        if ("auto".equals(arg)) {
            state.routing(null);
            ui.get().println("已恢复自动路径判定");
            return;
        }
        if ("agentic".equals(arg) || "graph".equals(arg)) {
            state.routing(arg);
            ui.get().println("已固定执行路径: " + arg + "（/routing auto 恢复自动判定）");
            return;
        }
        ui.get().println("routing 取值无效，用法: /routing auto | agentic | graph");
    }

    /**
     * 处理 /resume 命令：断点续跑未完成的复杂编排。
     * 带参 = 显式指定 goalId；无参 = 恢复当前会话最近一次编排任务（用户无需记住 goalId）。
     */
    private void handleResumeCommand(String line) {
        String arg = line.substring("/resume".length()).trim();
        if (arg.isEmpty()) {
            if (state.lastOrchestratedGoalId() == null || state.lastOrchestratedGoalId().isBlank()) {
                ui.get().println("当前会话没有可续跑的编排任务（/resume <goalId> 可显式指定）");
                return;
            }
            arg = state.lastOrchestratedGoalId();
        }
        ui.get().println("\033[90m正在从检查点续跑 goal " + arg + "（已完成节点不再重跑）…\033[0m");
        streamRenderer.resume(arg);
    }

    /**
     * 处理 /provider 命令：模型-服务商映射管理（llm_provider 表；模型与路由走 /api/admin）。
     * <pre>
     * /provider [list]                                    查看全量映射（含禁用行）
     * /provider add &lt;provider&gt; &lt;apiUrl&gt; &lt;模型1,模型2,...&gt;   新增映射并热刷新（免重启生效）
     * </pre>
     * 新增前需确保已设置对应环境变量 <PROVIDER大写>_API_KEY（如 MOONSHOT_API_KEY）。
     */
    /** 处理 /goal：查看 / 登记 / 完成（长期目标跨轮注入，不进入执行链） */
    private void handleGoalCommand(String line) {
        String arg = line.substring("/goal".length()).trim();
        if (arg.isEmpty() || "list".equalsIgnoreCase(arg)) {
            printActiveGoal();
            return;
        }
        if (arg.startsWith("done ")) {
            String id = arg.substring(5).trim();
            if (id.isEmpty()) {
                ui.get().println("用法: /goal done <id>");
                return;
            }
            try {
                api.completeActiveGoal(id);
                ui.get().println("\033[32m✓ 目标已完成 " + id + "\033[0m");
            } catch (IOException e) {
                ui.get().println("完成失败: " + e.getMessage());
            }
            return;
        }
        String sessionId = state.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            ui.get().println("当前没有会话，先发一条消息或 /new 再登记目标");
            return;
        }
        try {
            GoalView created = api.createActiveGoal(sessionId, arg);
            ui.get().println("\033[32m✓ 已登记长期目标 " + created.id() + "，之后每轮对话都会带上它\033[0m");
        } catch (IOException e) {
            ui.get().println("登记失败: " + e.getMessage());
        }
    }

    private void printActiveGoal() {
        String sessionId = state.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            ui.get().println("当前没有会话");
            return;
        }
        try {
            GoalView goal = api.activeGoal(sessionId);
            if (goal == null) {
                ui.get().println("当前会话没有活跃目标（/goal <目标描述> 登记）");
                return;
            }
            ui.get().println("活跃目标 " + goal.id() + "（" + goal.status() + "）");
            ui.get().println("  \033[90m" + goal.objective() + "\033[0m");
        } catch (IOException e) {
            ui.get().println("查询失败: " + e.getMessage());
        }
    }

    /** /schedule add 的参数形态："<cron>" <agent> <目标>——cron 含空格，必须加引号才不会被拆开 */
    private static final Pattern SCHEDULE_ADD = Pattern.compile("\"([^\"]+)\"\\s+(\\S+)\\s+(.+)");

    /** 处理 /schedule：列表（默认）/ 创建 / 删除 */
    private void handleScheduleCommand(String line) {
        String arg = line.substring("/schedule".length()).trim();
        if (arg.isEmpty() || "list".equalsIgnoreCase(arg)) {
            printSchedules();
            return;
        }
        if (arg.startsWith("add ")) {
            Matcher matcher = SCHEDULE_ADD.matcher(arg.substring(4).trim());
            if (!matcher.matches()) {
                ui.get().println("用法: /schedule add \"<cron>\" <agent> <目标描述>");
                ui.get().println("  例: /schedule add \"0 0 8 * * *\" general 每天八点汇总昨天的工作");
                return;
            }
            try {
                Schedule created = api.createSchedule(
                        matcher.group(3), matcher.group(2), matcher.group(1), null);
                ui.get().println("\033[32m✓ 已创建 " + created.id()
                        + "，下次触发 " + created.nextRunAt() + "\033[0m");
            } catch (IOException e) {
                ui.get().println("创建失败: " + e.getMessage());
            }
            return;
        }
        if (arg.startsWith("del ")) {
            String id = arg.substring(4).trim();
            if (id.isEmpty()) {
                ui.get().println("用法: /schedule del <id>");
                return;
            }
            try {
                api.deleteSchedule(id);
                ui.get().println("\033[32m✓ 已删除 " + id + "\033[0m");
            } catch (IOException e) {
                ui.get().println("删除失败: " + e.getMessage());
            }
            return;
        }
        ui.get().println("用法: /schedule [list] | add \"<cron>\" <agent> <目标> | del <id>");
    }

    private void printSchedules() {
        try {
            List<Schedule> items = api.listSchedules();
            if (items.isEmpty()) {
                ui.get().println("暂无定时任务（/schedule add \"<cron>\" <agent> <目标> 创建）");
                return;
            }
            ui.get().println("定时任务（共 " + items.size() + " 条）：");
            for (Schedule s : items) {
                String flag = s.enabled() ? "\033[32m●\033[0m" : "\033[90m○\033[0m";
                ui.get().println("  " + flag + " " + s.id() + "  " + s.cron()
                        + "  agent=" + s.agentName() + "  下次=" + s.nextRunAt());
                ui.get().println("     \033[90m" + s.objective() + "\033[0m");
            }
        } catch (IOException e) {
            ui.get().println("获取定时任务失败: " + e.getMessage());
        }
    }

    private void handleProviderCommand(String line) {
        String arg = line.substring("/provider".length()).trim();
        if (arg.isEmpty() || "list".equalsIgnoreCase(arg)) {
            try {
                List<ChatApiClient.AdminProvider> rows = api.listProviders();
                if (rows.isEmpty()) {
                    ui.get().println("暂无供应商（llm_provider 表为空）");
                    return;
                }
                ui.get().println("LLM 供应商（id | code → 端点，共 " + rows.size() + " 行）：");
                for (ChatApiClient.AdminProvider r : rows) {
                    String flag = r.enabled() != null && r.enabled() == 1
                            ? "\033[32m✓\033[0m" : "\033[31m✗\033[0m";
                    ui.get().println("  " + flag + " [" + r.id() + "] " + r.code()
                            + "  \033[90m" + r.baseUrl() + "\033[0m");
                }
                ui.get().println("\033[90m提示：模型/路由请使用 /api/admin/models 与 /api/admin/routes 管理\033[0m");
            } catch (IOException e) {
                ui.get().println("获取供应商失败: " + e.getMessage());
            }
            return;
        }
        if (arg.startsWith("add")) {
            String[] parts = arg.split("\\s+", 4);
            if (parts.length < 4) {
                ui.get().println("用法: /provider add <provider> <apiUrl> <模型1,模型2,...>");
                return;
            }
            String provider = parts[1];
            List<String> models = Arrays.stream(parts[3].split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (models.isEmpty()) {
                ui.get().println("至少提供一个模型名");
                return;
            }
            try {
                ChatApiClient.AddProviderResult result = api.addProvider(provider, parts[2], models);
                ui.get().println("\033[32m✓ 已登记供应商 id=" + result.providerId()
                        + "，模型 " + result.models() + " 个（配置校验通过并已热更新）\033[0m");
                ui.get().println("\033[90m提示：请确保已设置环境变量 " + provider.toUpperCase()
                        + "_API_KEY；模型默认能力为工具/思考/JSON 均支持，可用管理接口按需调整\033[0m");
            } catch (IOException e) {
                ui.get().println("新增供应商失败: " + e.getMessage());
            }
            return;
        }
        ui.get().println("用法: /provider [list] | /provider add <provider> <apiUrl> <模型1,模型2,...>");
    }
}
