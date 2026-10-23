package com.skevenxu.humanoid.ai.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.entity.BotTask;
import com.skevenxu.humanoid.entity.BotTaskStep;
import com.skevenxu.humanoid.mapper.BotTaskMapper;
import com.skevenxu.humanoid.mapper.BotTaskStepMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 任务管理工具：任务包含多个步骤，按当前会话隔离。 */
@Component
public class TaskTool {

    private static final Logger log = LoggerFactory.getLogger(TaskTool.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotTaskMapper taskMapper;
    private final BotTaskStepMapper stepMapper;

    public TaskTool(BotTaskMapper taskMapper, BotTaskStepMapper stepMapper) {
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
    }

    @Tool(description = "创建一个新任务，返回任务编号。适合需要多步骤完成的事情。")
    public String addTask(@ToolParam(description = "任务标题，例如：写周报") String title) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        if (title == null || title.isBlank()) {
            return "错误：任务标题不能为空";
        }
        if (title.length() > 200) {
            return "错误：任务标题过长（最多 200 字）";
        }

        BotTask task = new BotTask();
        task.setUserId(scope.userId());
        task.setMessageType(scope.messageType());
        task.setGroupId(scopeGroup(scope));
        task.setTitle(title.trim());
        task.setStatus(BotTask.STATUS_PENDING);
        task.setCreatedAt(LocalDateTime.now(ZONE));
        taskMapper.insert(task);

        log.info("工具调用 addTask user_id={} task_id={} title={}", scope.userId(), task.getId(), task.getTitle());
        return "已创建任务 #" + task.getId() + "：" + task.getTitle();
    }

    @Tool(description = "给任务添加一个步骤。taskId 省略时，添加到当前未完成的任务。")
    public String addTaskStep(@ToolParam(description = "步骤内容，例如：收集本周数据") String step,
                              @ToolParam(description = "任务编号，可选", required = false) Long taskId) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        if (step == null || step.isBlank()) {
            return "错误：步骤内容不能为空";
        }
        if (step.length() > 500) {
            return "错误：步骤内容过长（最多 500 字）";
        }

        BotTask task = resolve(scope, taskId);
        if (task == null) {
            return taskId == null ? "当前没有未完成任务，请先创建任务" : "错误：任务不存在";
        }

        BotTaskStep last = stepMapper.selectOne(new LambdaQueryWrapper<BotTaskStep>()
                .eq(BotTaskStep::getTaskId, task.getId())
                .orderByDesc(BotTaskStep::getStepNo)
                .last("LIMIT 1"));
        int stepNo = last == null || last.getStepNo() == null ? 1 : last.getStepNo() + 1;

        BotTaskStep entity = new BotTaskStep();
        entity.setTaskId(task.getId());
        entity.setStepNo(stepNo);
        entity.setContent(step.trim());
        entity.setStatus(BotTaskStep.STATUS_PENDING);
        entity.setAttempts(0);
        entity.setCreatedAt(LocalDateTime.now(ZONE));
        stepMapper.insert(entity);

        log.info("工具调用 addTaskStep task_id={} step_no={}", task.getId(), stepNo);
        return "已添加步骤 " + stepNo + " 到任务 #" + task.getId() + "：" + entity.getContent();
    }

    @Tool(description = "获取任务进度。taskId 省略时，查询当前未完成的任务。")
    public String getTaskProgress(@ToolParam(description = "任务编号，可选", required = false) Long taskId) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        BotTask task = resolve(scope, taskId);
        if (task == null) {
            return taskId == null ? "当前没有未完成的任务" : "错误：任务不存在";
        }
        log.info("工具调用 getTaskProgress task_id={}", task.getId());
        return render(task);
    }

    @Tool(description = "把任务标记为已完成，同时把所有未完成步骤标记完成。taskId 省略时操作当前未完成任务。")
    public String completeTask(@ToolParam(description = "任务编号，可选", required = false) Long taskId) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        BotTask task = resolve(scope, taskId);
        if (task == null) {
            return taskId == null ? "当前没有未完成的任务" : "错误：任务不存在";
        }

        LocalDateTime now = LocalDateTime.now(ZONE);
        for (BotTaskStep step : steps(task.getId())) {
            if (!BotTaskStep.STATUS_COMPLETED.equals(step.getStatus())) {
                step.setStatus(BotTaskStep.STATUS_COMPLETED);
                step.setCompletedAt(now);
                stepMapper.updateById(step);
            }
        }
        task.setStatus(BotTask.STATUS_COMPLETED);
        task.setCompletedAt(now);
        taskMapper.updateById(task);

        log.info("工具调用 completeTask task_id={}", task.getId());
        return "任务 #" + task.getId() + "「" + task.getTitle() + "」已完成";
    }

    @Tool(description = "完成任务的第 N 个步骤（从 1 开始）。taskId 省略时操作当前未完成任务。")
    public String completeStep(@ToolParam(description = "步骤序号，从 1 开始") Integer stepNo,
                               @ToolParam(description = "任务编号，可选", required = false) Long taskId) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        if (stepNo == null || stepNo < 1) {
            return "错误：步骤序号必须从 1 开始";
        }
        BotTask task = resolve(scope, taskId);
        if (task == null) {
            return taskId == null ? "当前没有未完成的任务" : "错误：任务不存在";
        }

        BotTaskStep step = stepMapper.selectOne(new LambdaQueryWrapper<BotTaskStep>()
                .eq(BotTaskStep::getTaskId, task.getId())
                .eq(BotTaskStep::getStepNo, stepNo));
        if (step == null) {
            return "错误：任务 #" + task.getId() + " 没有第 " + stepNo + " 步";
        }
        if (BotTaskStep.STATUS_COMPLETED.equals(step.getStatus())) {
            return "步骤 " + stepNo + " 已经是完成状态";
        }

        step.setStatus(BotTaskStep.STATUS_COMPLETED);
        step.setCompletedAt(LocalDateTime.now(ZONE));
        stepMapper.updateById(step);

        if (BotTask.STATUS_PENDING.equals(task.getStatus())) {
            task.setStatus(BotTask.STATUS_IN_PROGRESS);
            taskMapper.updateById(task);
        }

        log.info("工具调用 completeStep task_id={} step_no={}", task.getId(), stepNo);
        return "已完成步骤 " + stepNo + "：" + step.getContent() + "\n" + render(task);
    }

    @Tool(description = "开始执行任务：标记为进行中，后台执行器会逐步自动完成各步骤。taskId 省略时取当前任务。")
    public String startTask(@ToolParam(description = "任务编号，可选", required = false) Long taskId) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        BotTask task = resolve(scope, taskId);
        if (task == null) {
            return taskId == null ? "当前没有可执行的任务" : "错误：任务不存在";
        }
        if (BotTask.STATUS_IN_PROGRESS.equals(task.getStatus())) {
            return "任务 #" + task.getId() + " 已经在执行中";
        }
        if (BotTask.STATUS_COMPLETED.equals(task.getStatus())) {
            return "任务 #" + task.getId() + " 已完成，无需执行";
        }
        if (steps(task.getId()).isEmpty()) {
            return "错误：任务 #" + task.getId() + " 还没有步骤，请先添加步骤";
        }

        task.setStatus(BotTask.STATUS_IN_PROGRESS);
        task.setPauseReason(null);
        taskMapper.updateById(task);

        log.info("工具调用 startTask task_id={}", task.getId());
        return "任务 #" + task.getId() + "「" + task.getTitle() + "」已开始执行，后台会逐步完成并汇报进度。";
    }

    @Tool(description = "暂停任务并记录原因。后台执行器遇到无法完成的步骤时使用。taskId 省略时取当前任务。")
    public String pauseTask(@ToolParam(description = "暂停原因") String reason,
                            @ToolParam(description = "任务编号，可选", required = false) Long taskId) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        if (reason == null || reason.isBlank()) {
            return "错误：暂停原因不能为空";
        }
        BotTask task = resolve(scope, taskId);
        if (task == null) {
            return taskId == null ? "当前没有可暂停的任务" : "错误：任务不存在";
        }

        task.setStatus(BotTask.STATUS_PAUSED);
        task.setPauseReason(reason.trim());
        taskMapper.updateById(task);

        log.info("工具调用 pauseTask task_id={} reason={}", task.getId(), reason);
        return "任务 #" + task.getId() + " 已暂停：" + reason.trim();
    }

    @Tool(description = "恢复被暂停的任务，后台执行器会继续执行。taskId 省略时取当前任务。")
    public String resumeTask(@ToolParam(description = "任务编号，可选", required = false) Long taskId) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        BotTask task = resolve(scope, taskId);
        if (task == null) {
            return taskId == null ? "当前没有可恢复的任务" : "错误：任务不存在";
        }
        if (!BotTask.STATUS_PAUSED.equals(task.getStatus())) {
            return "任务 #" + task.getId() + " 当前不是暂停状态";
        }

        task.setStatus(BotTask.STATUS_IN_PROGRESS);
        task.setPauseReason(null);
        taskMapper.updateById(task);

        log.info("工具调用 resumeTask task_id={}", task.getId());
        return "任务 #" + task.getId() + "「" + task.getTitle() + "」已恢复执行。";
    }

    @Tool(description = "删除任务及其所有步骤。taskId 省略时删除当前未完成任务。")
    public String deleteTask(@ToolParam(description = "任务编号，可选", required = false) Long taskId) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        BotTask task = resolve(scope, taskId);
        if (task == null) {
            return taskId == null ? "当前没有未完成的任务" : "错误：任务不存在";
        }

        stepMapper.delete(new LambdaQueryWrapper<BotTaskStep>()
                .eq(BotTaskStep::getTaskId, task.getId()));
        taskMapper.deleteById(task.getId());

        log.info("工具调用 deleteTask task_id={}", task.getId());
        return "已删除任务 #" + task.getId() + "「" + task.getTitle() + "」";
    }

    /** 供系统提示词使用：优先进行中，其次未开始、暂停，最后是最近 24 小时更新过的任务。 */
    public String activeTaskBrief(Long userId, String messageType, Long groupId) {
        long scopeGroup = "group".equals(messageType) && groupId != null ? groupId : 0L;
        BotTask task = findByStatus(userId, messageType, scopeGroup, BotTask.STATUS_IN_PROGRESS);
        if (task == null) {
            task = findByStatus(userId, messageType, scopeGroup, BotTask.STATUS_PENDING);
        }
        if (task == null) {
            task = findByStatus(userId, messageType, scopeGroup, BotTask.STATUS_PAUSED);
        }
        if (task == null) {
            task = taskMapper.selectOne(new LambdaQueryWrapper<BotTask>()
                    .eq(BotTask::getUserId, userId)
                    .eq(BotTask::getMessageType, messageType)
                    .eq(BotTask::getGroupId, scopeGroup)
                    .ge(BotTask::getUpdatedAt, LocalDateTime.now(ZONE).minusHours(24))
                    .orderByDesc(BotTask::getId)
                    .last("LIMIT 1"));
        }
        return task == null ? "" : render(task);
    }

    private BotTask findByStatus(Long userId, String messageType, long scopeGroup, String status) {
        return taskMapper.selectOne(new LambdaQueryWrapper<BotTask>()
                .eq(BotTask::getUserId, userId)
                .eq(BotTask::getMessageType, messageType)
                .eq(BotTask::getGroupId, scopeGroup)
                .eq(BotTask::getStatus, status)
                .orderByDesc(BotTask::getId)
                .last("LIMIT 1"));
    }

    /** taskId 为空时优先取当前未完成任务，没有则取最近一个任务；否则按 id + 会话范围查找。 */
    private BotTask resolve(ToolContext.ChatScope scope, Long taskId) {
        LambdaQueryWrapper<BotTask> query = new LambdaQueryWrapper<BotTask>()
                .eq(BotTask::getUserId, scope.userId())
                .eq(BotTask::getMessageType, scope.messageType())
                .eq(BotTask::getGroupId, scopeGroup(scope));
        if (taskId == null) {
            BotTask pending = taskMapper.selectOne(query.clone()
                    .eq(BotTask::getStatus, BotTask.STATUS_PENDING)
                    .orderByDesc(BotTask::getId)
                    .last("LIMIT 1"));
            if (pending != null) {
                return pending;
            }
            return taskMapper.selectOne(query.orderByDesc(BotTask::getId).last("LIMIT 1"));
        }
        return taskMapper.selectOne(query.eq(BotTask::getId, taskId));
    }

    private List<BotTaskStep> steps(Long taskId) {
        return stepMapper.selectList(new LambdaQueryWrapper<BotTaskStep>()
                .eq(BotTaskStep::getTaskId, taskId)
                .orderByAsc(BotTaskStep::getStepNo));
    }

    private String render(BotTask task) {
        List<BotTaskStep> steps = steps(task.getId());
        long done = steps.stream()
                .filter(step -> BotTaskStep.STATUS_COMPLETED.equals(step.getStatus()))
                .count();

        String statusText = switch (task.getStatus()) {
            case BotTask.STATUS_IN_PROGRESS -> "▶ 进行中";
            case BotTask.STATUS_PAUSED -> "⏸ 已暂停（" + task.getPauseReason() + "）";
            case BotTask.STATUS_COMPLETED -> "✅ 已完成";
            default -> "未开始";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("任务 #").append(task.getId()).append("「").append(task.getTitle()).append("」")
                .append(statusText)
                .append("（").append(done).append("/").append(steps.size()).append("）");
        for (BotTaskStep step : steps) {
            boolean completed = BotTaskStep.STATUS_COMPLETED.equals(step.getStatus());
            sb.append("\n  ").append(step.getStepNo()).append(". ")
                    .append(completed ? "☑" : "☐").append(" ").append(step.getContent());
        }
        return sb.toString();
    }

    private static long scopeGroup(ToolContext.ChatScope scope) {
        return "group".equals(scope.messageType()) && scope.groupId() != null ? scope.groupId() : 0L;
    }
}
