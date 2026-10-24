package com.skevenxu.humanoid.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.ai.tools.ToolContext;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotTask;
import com.skevenxu.humanoid.entity.BotTaskStep;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotTaskMapper;
import com.skevenxu.humanoid.mapper.BotTaskStepMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 后台任务执行器：逐步执行 IN_PROGRESS 任务，汇报进度，无法完成时暂停。 */
@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ChatClient chatClient;
    private final HumanoidTools humanoidTools;
    private final BotTaskMapper taskMapper;
    private final BotTaskStepMapper stepMapper;
    private final LlbotApiClient llbotApiClient;
    private final BotConversationMapper conversationMapper;
    private final ContextStore contextStore;
    private final ToolCallLogger toolCallLogger;
    private final ToolProgressNotifier toolProgressNotifier;
    private final String executorPrompt;
    private final boolean enabled;
    private final int maxAttempts;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public TaskExecutionService(ChatClient.Builder chatClientBuilder,
                                HumanoidTools humanoidTools,
                                BotTaskMapper taskMapper,
                                BotTaskStepMapper stepMapper,
                                LlbotApiClient llbotApiClient,
                                BotConversationMapper conversationMapper,
                                ContextStore contextStore,
                                ToolCallLogger toolCallLogger,
                                ToolProgressNotifier toolProgressNotifier,
                                @Value("classpath:prompts/task-executor-prompt.txt") Resource executorPrompt,
                                @Value("${humanoid.task.executor.enabled:true}") boolean enabled,
                                @Value("${humanoid.task.executor.max-attempts:3}") int maxAttempts) {
        var executorCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(humanoidTools.createForExecutor(chatClientBuilder.build()).toArray())
                .build()
                .getToolCallbacks();
        this.chatClient = chatClientBuilder
                .defaultToolCallbacks(toolCallLogger.wrap(executorCallbacks))
                .build();
        this.taskMapper = taskMapper;
        this.humanoidTools = humanoidTools;
        this.stepMapper = stepMapper;
        this.toolCallLogger = toolCallLogger;
        this.toolProgressNotifier = toolProgressNotifier;
        this.llbotApiClient = llbotApiClient;
        this.conversationMapper = conversationMapper;
        this.contextStore = contextStore;
        this.executorPrompt = loadPrompt(executorPrompt);
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(cron = "${humanoid.task.executor.cron:0 * * * * *}")
    public void tick() {
        if (!enabled) {
            return;
        }
        List<BotTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<BotTask>()
                .eq(BotTask::getStatus, BotTask.STATUS_IN_PROGRESS)
                .orderByAsc(BotTask::getUpdatedAt)
                .last("LIMIT 5"));
        log.info("任务执行扫描触发, 进行中任务数={}", tasks.size());
        for (BotTask task : tasks) {
            if (!running.add(task.getId())) {
                continue;
            }
            try {
                executeNextStep(task);
            } catch (Exception e) {
                log.error("任务执行失败 task_id={}", task.getId(), e);
            } finally {
                running.remove(task.getId());
            }
        }
    }

    private void executeNextStep(BotTask task) {
        BotTaskStep step = stepMapper.selectOne(new LambdaQueryWrapper<BotTaskStep>()
                .eq(BotTaskStep::getTaskId, task.getId())
                .eq(BotTaskStep::getStatus, BotTaskStep.STATUS_PENDING)
                .orderByAsc(BotTaskStep::getStepNo)
                .last("LIMIT 1"));
        if (step == null) {
            finishTask(task);
            return;
        }

        int attempts = (step.getAttempts() == null ? 0 : step.getAttempts()) + 1;
        step.setAttempts(attempts);
        stepMapper.updateById(step);
        if (attempts > maxAttempts) {
            pauseTask(task, "步骤 " + step.getStepNo() + " 多次执行失败");
            return;
        }

        List<BotTaskStep> steps = steps(task.getId());
        String context = buildContext(task, step, steps);
        log.info("开始执行任务 task_id={} step_no={} attempt={}", task.getId(), step.getStepNo(), attempts);

        ToolContext.set(new ToolContext.ChatScope(
                task.getUserId(),
                task.getMessageType(),
                task.getGroupId() != null && task.getGroupId() != 0 ? task.getGroupId() : null,
                true));
        toolProgressNotifier.begin();
        try {
            chatClient.prompt()
                    .system(executorPrompt)
                    .user(context)
                    .call()
                    .content();
        } finally {
            toolProgressNotifier.end();
            ToolContext.clear();
        }

        BotTask latest = taskMapper.selectById(task.getId());
        if (latest == null) {
            return;
        }
        if (BotTask.STATUS_PAUSED.equals(latest.getStatus())) {
            notifyUser(latest, "任务 #" + latest.getId() + "「" + latest.getTitle() + "」已暂停："
                    + latest.getPauseReason() + "\n处理好后告诉我，我再继续。");
            return;
        }
        if (BotTask.STATUS_COMPLETED.equals(latest.getStatus())) {
            notifyUser(latest, "任务 #" + latest.getId() + "「" + latest.getTitle() + "」已全部完成 🎉");
            return;
        }

        BotTaskStep after = stepMapper.selectById(step.getId());
        if (after != null && BotTaskStep.STATUS_COMPLETED.equals(after.getStatus())) {
            List<BotTaskStep> current = steps(task.getId());
            long done = current.stream()
                    .filter(s -> BotTaskStep.STATUS_COMPLETED.equals(s.getStatus()))
                    .count();
            notifyUser(latest, "任务 #" + latest.getId() + "「" + latest.getTitle() + "」：步骤 "
                    + after.getStepNo() + "「" + after.getContent() + "」已完成，进度 "
                    + done + "/" + current.size() + "。");
            if (done == current.size()) {
                finishTask(latest);
            }
        } else if (attempts >= maxAttempts) {
            pauseTask(latest, "步骤 " + step.getStepNo() + "「" + step.getContent() + "」多次执行未完成");
        } else {
            log.info("步骤尚未完成，下轮重试 task_id={} step_no={} attempt={}",
                    task.getId(), step.getStepNo(), attempts);
        }
    }

    private String buildContext(BotTask task, BotTaskStep current, List<BotTaskStep> steps) {
        StringBuilder sb = new StringBuilder();
        LocalDateTime now = LocalDateTime.now(ZONE);
        sb.append("当前时间：").append(now.format(FORMAT))
                .append("（").append(now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA))
                .append("）\n");
        sb.append("任务 #").append(task.getId()).append("：")
                .append(PromptSanitizer.clean(task.getTitle())).append("\n");
        sb.append("全部步骤：\n");
        for (BotTaskStep step : steps) {
            boolean completed = BotTaskStep.STATUS_COMPLETED.equals(step.getStatus());
            sb.append("  ").append(step.getStepNo()).append(". ")
                    .append(completed ? "[x]" : "[ ]").append(" ")
                    .append(PromptSanitizer.clean(step.getContent()));
            if (step.getId().equals(current.getId())) {
                sb.append("  ← 当前步骤");
            }
            sb.append("\n");
        }
        sb.append("\n请执行当前步骤：第 ").append(current.getStepNo())
                .append(" 步「").append(PromptSanitizer.clean(current.getContent())).append("」。");
        sb.append("\n调用 completeStep / pauseTask 时请带上 taskId=").append(task.getId()).append("。");
        sb.append("\n文件目录根路径：").append(PromptSanitizer.clean(
                        humanoidTools.userRoot(task.getUserId())))
                .append("（共享目录：").append(PromptSanitizer.clean(humanoidTools.sharedDirs()))
                .append("）。相对路径默认相对用户目录。");
        return sb.toString();
    }

    private void finishTask(BotTask task) {
        task.setStatus(BotTask.STATUS_COMPLETED);
        task.setCompletedAt(LocalDateTime.now(ZONE));
        taskMapper.updateById(task);
        notifyUser(task, "任务 #" + task.getId() + "「" + task.getTitle() + "」已全部完成 🎉");
        log.info("任务完成 task_id={}", task.getId());
    }

    private void pauseTask(BotTask task, String reason) {
        task.setStatus(BotTask.STATUS_PAUSED);
        task.setPauseReason(reason);
        taskMapper.updateById(task);
        notifyUser(task, "任务 #" + task.getId() + "「" + task.getTitle() + "」已暂停：" + reason
                + "\n处理好后告诉我，我再继续。");
        log.info("任务暂停 task_id={} reason={}", task.getId(), reason);
    }

    private void notifyUser(BotTask task, String text) {
        Optional<Long> messageId = "group".equals(task.getMessageType())
                ? llbotApiClient.sendGroup(task.getGroupId(), text)
                : llbotApiClient.sendPrivate(task.getUserId(), text);
        if (messageId.isEmpty()) {
            log.warn("任务通知发送失败 task_id={}", task.getId());
            return;
        }

        BotConversation bot = new BotConversation();
        bot.setUserId(task.getUserId());
        bot.setRole("bot");
        bot.setMessageType(task.getMessageType());
        if ("group".equals(task.getMessageType())) {
            bot.setGroupId(task.getGroupId());
        }
        bot.setOnebotMessageId(messageId.get());
        bot.setContent(text);
        bot.setEventTime(LocalDateTime.now(ZONE));
        bot.setCreatedAt(LocalDateTime.now(ZONE));
        conversationMapper.insert(bot);
        contextStore.appendAssistant(task.getUserId(), task.getMessageType(), task.getGroupId(), text, java.util.List.of());
    }

    private List<BotTaskStep> steps(Long taskId) {
        return stepMapper.selectList(new LambdaQueryWrapper<BotTaskStep>()
                .eq(BotTaskStep::getTaskId, taskId)
                .orderByAsc(BotTaskStep::getStepNo));
    }

    private static String loadPrompt(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 prompts/task-executor-prompt.txt", e);
        }
    }
}
