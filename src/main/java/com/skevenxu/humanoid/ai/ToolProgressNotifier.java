package com.skevenxu.humanoid.ai;

import com.skevenxu.humanoid.ai.tools.ToolContext;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具调用进度提示：第一轮工具调用前通知用户已开始处理，
 * 后续步骤按间隔节流汇报，单个工具执行过久时再补一条“还在处理中”。
 * 只在用户消息触发的 AI 回合中启用（由 ChatScope.progressEnabled 控制）。
 */
@Component
public class ToolProgressNotifier {

    private static final Logger log = LoggerFactory.getLogger(ToolProgressNotifier.class);

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("WebFetch", "抓取网页"),
            Map.entry("BraveWebSearch", "搜索网页"),
            Map.entry("downloadFile", "下载文件"),
            Map.entry("readFile", "读取文件"),
            Map.entry("writeFile", "写入文件"),
            Map.entry("editFile", "修改文件"),
            Map.entry("listDirectory", "查看目录"),
            Map.entry("listFiles", "查看目录"),
            Map.entry("grep", "检索文件"),
            Map.entry("glob", "查找文件"),
            Map.entry("runShell", "执行命令"),
            Map.entry("describeImage", "查看图片"),
            Map.entry("scheduleReminder", "设置提醒"),
            Map.entry("addTask", "创建任务"),
            Map.entry("addTaskStep", "添加任务步骤"),
            Map.entry("startTask", "开始任务"),
            Map.entry("pauseTask", "暂停任务"),
            Map.entry("resumeTask", "恢复任务"),
            Map.entry("completeTask", "完成任务"),
            Map.entry("completeStep", "完成步骤"),
            Map.entry("deleteTask", "删除任务"),
            Map.entry("getTaskProgress", "查看任务进度"),
            Map.entry("registerSkill", "登记技能"),
            Map.entry("useSkill", "加载技能"),
            Map.entry("listSkills", "查看技能"),
            Map.entry("browser_navigate", "打开网页"),
            Map.entry("browser_snapshot", "读取页面"),
            Map.entry("browser_click", "点击页面"),
            Map.entry("browser_type", "填写表单"),
            Map.entry("browser_fill_form", "填写表单"),
            Map.entry("browser_press_key", "操作页面"),
            Map.entry("browser_take_screenshot", "截图"),
            Map.entry("browser_evaluate", "执行页面脚本"),
            Map.entry("browser_wait_for", "等待页面"),
            Map.entry("browser_tabs", "切换页面"),
            Map.entry("browser_navigate_back", "返回上一页"),
            Map.entry("browser_file_upload", "上传文件"),
            Map.entry("browser_handle_dialog", "处理弹窗"),
            Map.entry("browser_drag", "拖拽页面"),
            Map.entry("browser_drop", "拖拽页面"),
            Map.entry("browser_hover", "悬停页面"),
            Map.entry("browser_select_option", "选择选项"),
            Map.entry("browser_find", "页面查找"),
            Map.entry("browser_close", "关闭页面"),
            Map.entry("browser_resize", "调整页面"),
            Map.entry("browser_emulate_media", "模拟媒体"),
            Map.entry("browser_network_request", "检查网络请求"),
            Map.entry("browser_network_requests", "检查网络请求"),
            Map.entry("browser_console_messages", "读取控制台")
    );

    /** 本身就会给用户发消息、或瞬间完成且无展示价值的工具，不做进度提示。 */
    private static final Set<String> QUIET_TOOLS = Set.of(
            "currentTime", "listTools", "TodoWrite",
            "sendImage", "sendFace", "sendMessages", "sendFile", "getFavoriteFaces", "renderSvg",
            "sendVoice", "shareVideo", "videoQueueStatus", "nextVideo"
    );

    private final LlbotApiClient llbotApiClient;
    private final boolean enabled;
    private final long minIntervalMillis;
    private final int maxMessages;
    private final long heartbeatSeconds;
    private final ScheduledExecutorService scheduler;
    private final ThreadLocal<TurnState> currentTurn = new ThreadLocal<>();

    public ToolProgressNotifier(LlbotApiClient llbotApiClient,
                                @Value("${humanoid.ai.tool-progress.enabled:true}") boolean enabled,
                                @Value("${humanoid.ai.tool-progress.min-interval-seconds:8}") long minIntervalSeconds,
                                @Value("${humanoid.ai.tool-progress.max-messages:8}") int maxMessages,
                                @Value("${humanoid.ai.tool-progress.heartbeat-seconds:25}") long heartbeatSeconds) {
        this.llbotApiClient = llbotApiClient;
        this.enabled = enabled;
        this.minIntervalMillis = Math.max(0, minIntervalSeconds) * 1000;
        this.maxMessages = Math.max(0, maxMessages);
        this.heartbeatSeconds = Math.max(0, heartbeatSeconds);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tool-progress-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 开始一个新的 AI 回合。 */
    public void begin() {
        if (enabled) {
            currentTurn.set(new TurnState());
        }
    }

    /** 结束当前 AI 回合。 */
    public void end() {
        currentTurn.remove();
    }

    /** 工具即将执行，按需发送进度消息；返回的对象负责结束心跳检测。 */
    public ToolExecution toolStarted(String toolName) {
        if (!enabled || isQuiet(toolName)) {
            return ToolExecution.NOOP;
        }
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null || !scope.progressEnabled()) {
            return ToolExecution.NOOP;
        }

        TurnState turn = currentTurn.get();
        if (turn == null) {
            turn = new TurnState();
            currentTurn.set(turn);
        }
        long now = System.currentTimeMillis();
        int step = ++turn.steps;

        boolean send;
        if (step == 1) {
            send = true;
        } else {
            send = turn.messages < maxMessages && now - turn.lastSentAt >= minIntervalMillis;
        }
        if (send) {
            String text = step == 1
                    ? "收到，我先" + label(toolName) + "，可能需要一点时间～"
                    : "第 " + step + " 步：" + label(toolName) + "…";
            turn.lastSentAt = now;
            turn.messages++;
            sendMessage(scope, text);
        }
        return scheduleHeartbeat(scope, turn);
    }

    private ToolExecution scheduleHeartbeat(ToolContext.ChatScope scope, TurnState turn) {
        if (heartbeatSeconds <= 0) {
            return ToolExecution.NOOP;
        }
        AtomicBoolean active = new AtomicBoolean(true);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (!active.get()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - turn.lastSentAt < heartbeatSeconds * 1000) {
                return;
            }
            turn.lastSentAt = now;
            sendMessage(scope, "还在处理中，请稍等～");
        }, heartbeatSeconds, TimeUnit.SECONDS);
        return new ToolExecution(active, future);
    }

    private void sendMessage(ToolContext.ChatScope scope, String text) {
        try {
            Optional<Long> messageId = "group".equals(scope.messageType()) && scope.groupId() != null
                    ? llbotApiClient.sendGroup(scope.groupId(), text)
                    : llbotApiClient.sendPrivate(scope.userId(), text);
            if (messageId.isEmpty()) {
                log.warn("工具进度提示发送失败 user_id={} group_id={}",
                        scope.userId(), scope.groupId());
            }
        } catch (Exception e) {
            log.warn("工具进度提示发送异常 user_id={}: {}", scope.userId(), e.toString());
        }
    }

    private static boolean isQuiet(String toolName) {
        return toolName == null || QUIET_TOOLS.contains(toolName);
    }

    private static String label(String toolName) {
        String known = LABELS.get(toolName);
        if (known != null) {
            return known;
        }
        if (toolName.startsWith("browser_")) {
            return "操作浏览器";
        }
        return "执行 " + toolName;
    }

    private static final class TurnState {
        private int steps;
        private int messages;
        private volatile long lastSentAt;
    }

    /** 单次工具执行的心跳句柄；close 后不再发送“还在处理中”。 */
    public static final class ToolExecution implements AutoCloseable {

        static final ToolExecution NOOP = new ToolExecution(null, null);

        private final AtomicBoolean active;
        private final ScheduledFuture<?> heartbeat;

        private ToolExecution(AtomicBoolean active, ScheduledFuture<?> heartbeat) {
            this.active = active;
            this.heartbeat = heartbeat;
        }

        @Override
        public void close() {
            if (active != null) {
                active.set(false);
            }
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }
}
