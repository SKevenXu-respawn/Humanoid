package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.dto.AdminDtos.TaskCreateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.TaskDetailView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.TaskStepView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.TaskView;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import com.skevenxu.humanoid.entity.BotTask;
import com.skevenxu.humanoid.entity.BotTaskStep;
import com.skevenxu.humanoid.mapper.BotTaskMapper;
import com.skevenxu.humanoid.mapper.BotTaskStepMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** 任务管理（管理端直接操作表，不依赖 ToolContext）。 */
@Service
public class AdminTaskService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotTaskMapper taskMapper;
    private final BotTaskStepMapper stepMapper;

    public AdminTaskService(BotTaskMapper taskMapper, BotTaskStepMapper stepMapper) {
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
    }

    public PageResponse<TaskView> page(Integer page, Integer size, String status,
                                       Long userId, String messageType, Long groupId, String keyword) {
        LambdaQueryWrapper<BotTask> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            query.eq(BotTask::getStatus, status.trim().toUpperCase());
        }
        if (userId != null) {
            query.eq(BotTask::getUserId, userId);
        }
        if (StringUtils.hasText(messageType)) {
            String type = AdminSupport.messageType(messageType);
            query.eq(BotTask::getMessageType, type)
                    .eq(BotTask::getGroupId, AdminSupport.scopeGroup(type, groupId));
        }
        if (StringUtils.hasText(keyword)) {
            query.like(BotTask::getTitle, keyword.trim());
        }
        query.orderByDesc(BotTask::getId);

        Page<BotTask> result = taskMapper.selectPage(new Page<>(page, size), query);
        Map<Long, long[]> counts = stepCounts(result.getRecords().stream().map(BotTask::getId).toList());
        List<TaskView> views = result.getRecords().stream()
                .map(task -> toView(task, counts.getOrDefault(task.getId(), new long[]{0, 0})))
                .toList();
        return new PageResponse<>(views, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public TaskDetailView detail(Long id) {
        BotTask task = getTask(id);
        List<BotTaskStep> steps = steps(id);
        long done = steps.stream().filter(s -> BotTaskStep.STATUS_COMPLETED.equals(s.getStatus())).count();
        TaskView view = toView(task, new long[]{done, steps.size()});
        return new TaskDetailView(view, steps.stream().map(AdminTaskService::toStepView).toList());
    }

    @Transactional
    public TaskDetailView create(TaskCreateRequest request) {
        if (request == null || request.userId() == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String title = AdminSupport.requireText(request.title(), 200, "title");
        String type = AdminSupport.messageType(request.messageType());

        LocalDateTime now = LocalDateTime.now(ZONE);
        BotTask task = new BotTask();
        task.setUserId(request.userId());
        task.setMessageType(type);
        task.setGroupId(AdminSupport.scopeGroup(type, request.groupId()));
        task.setTitle(title);
        task.setStatus(BotTask.STATUS_PENDING);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);

        List<String> steps = request.steps() == null ? List.of() : request.steps();
        if (steps.size() > 50) {
            throw new IllegalArgumentException("步骤不能超过 50 个");
        }
        int stepNo = 1;
        for (String content : steps) {
            if (content == null || content.isBlank()) {
                continue;
            }
            BotTaskStep step = new BotTaskStep();
            step.setTaskId(task.getId());
            step.setStepNo(stepNo++);
            step.setContent(AdminSupport.requireText(content, 500, "step"));
            step.setStatus(BotTaskStep.STATUS_PENDING);
            step.setAttempts(0);
            step.setCreatedAt(now);
            stepMapper.insert(step);
        }
        return detail(task.getId());
    }

    public TaskDetailView addStep(Long id, String content) {
        BotTask task = getTask(id);
        if (BotTask.STATUS_COMPLETED.equals(task.getStatus())) {
            throw new IllegalStateException("已完成的任务不能再加步骤");
        }
        String text = AdminSupport.requireText(content, 500, "step");
        List<BotTaskStep> steps = steps(id);
        int nextNo = steps.isEmpty() ? 1 : steps.get(steps.size() - 1).getStepNo() + 1;
        BotTaskStep step = new BotTaskStep();
        step.setTaskId(id);
        step.setStepNo(nextNo);
        step.setContent(text);
        step.setStatus(BotTaskStep.STATUS_PENDING);
        step.setAttempts(0);
        step.setCreatedAt(LocalDateTime.now(ZONE));
        stepMapper.insert(step);
        touch(id);
        return detail(id);
    }

    public TaskDetailView start(Long id) {
        BotTask task = getTask(id);
        if (!BotTask.STATUS_PENDING.equals(task.getStatus())) {
            throw new IllegalStateException("只有未开始的任务可以开始");
        }
        changeStatus(id, BotTask.STATUS_IN_PROGRESS, null);
        return detail(id);
    }

    public TaskDetailView resume(Long id) {
        BotTask task = getTask(id);
        if (!BotTask.STATUS_PAUSED.equals(task.getStatus())) {
            throw new IllegalStateException("只有暂停中的任务可以恢复");
        }
        changeStatus(id, BotTask.STATUS_IN_PROGRESS, null);
        return detail(id);
    }

    public TaskDetailView pause(Long id, String reason) {
        BotTask task = getTask(id);
        if (!BotTask.STATUS_IN_PROGRESS.equals(task.getStatus())) {
            throw new IllegalStateException("只有进行中的任务可以暂停");
        }
        changeStatus(id, BotTask.STATUS_PAUSED,
                AdminSupport.requireText(reason, 500, "reason"));
        return detail(id);
    }

    private void changeStatus(Long id, String status, String pauseReason) {
        taskMapper.update(null, new LambdaUpdateWrapper<BotTask>()
                .eq(BotTask::getId, id)
                .set(BotTask::getStatus, status)
                .set(BotTask::getPauseReason, pauseReason)
                .set(BotTask::getUpdatedAt, LocalDateTime.now(ZONE)));
    }

    private void touch(Long id) {
        taskMapper.update(null, new LambdaUpdateWrapper<BotTask>()
                .eq(BotTask::getId, id)
                .set(BotTask::getUpdatedAt, LocalDateTime.now(ZONE)));
    }

    @Transactional
    public TaskDetailView complete(Long id) {
        BotTask task = getTask(id);
        if (BotTask.STATUS_COMPLETED.equals(task.getStatus())) {
            throw new IllegalStateException("任务已完成");
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        for (BotTaskStep step : steps(id)) {
            if (!BotTaskStep.STATUS_COMPLETED.equals(step.getStatus())) {
                step.setStatus(BotTaskStep.STATUS_COMPLETED);
                step.setCompletedAt(now);
                stepMapper.updateById(step);
            }
        }
        task.setStatus(BotTask.STATUS_COMPLETED);
        task.setCompletedAt(now);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
        return detail(id);
    }

    @Transactional
    public TaskDetailView completeStep(Long taskId, Long stepId) {
        BotTask task = getTask(taskId);
        BotTaskStep step = stepMapper.selectById(stepId);
        if (step == null || !taskId.equals(step.getTaskId())) {
            throw new NoSuchElementException("步骤不存在: " + stepId);
        }
        if (!BotTaskStep.STATUS_COMPLETED.equals(step.getStatus())) {
            step.setStatus(BotTaskStep.STATUS_COMPLETED);
            step.setCompletedAt(LocalDateTime.now(ZONE));
            stepMapper.updateById(step);
        }
        if (BotTask.STATUS_PENDING.equals(task.getStatus())) {
            changeStatus(taskId, BotTask.STATUS_IN_PROGRESS, task.getPauseReason());
        }
        List<BotTaskStep> all = steps(taskId);
        boolean allDone = !all.isEmpty()
                && all.stream().allMatch(s -> BotTaskStep.STATUS_COMPLETED.equals(s.getStatus()));
        if (allDone) {
            task.setStatus(BotTask.STATUS_COMPLETED);
            task.setCompletedAt(LocalDateTime.now(ZONE));
            task.setUpdatedAt(LocalDateTime.now(ZONE));
            taskMapper.updateById(task);
        } else {
            touch(taskId);
        }
        return detail(taskId);
    }

    @Transactional
    public void delete(Long id) {
        getTask(id);
        stepMapper.delete(new LambdaQueryWrapper<BotTaskStep>().eq(BotTaskStep::getTaskId, id));
        taskMapper.deleteById(id);
    }

    private BotTask getTask(Long id) {
        BotTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在: " + id);
        }
        return task;
    }

    private List<BotTaskStep> steps(Long taskId) {
        return stepMapper.selectList(new LambdaQueryWrapper<BotTaskStep>()
                .eq(BotTaskStep::getTaskId, taskId)
                .orderByAsc(BotTaskStep::getStepNo));
    }

    private Map<Long, long[]> stepCounts(List<Long> taskIds) {
        Map<Long, long[]> counts = new HashMap<>();
        if (taskIds.isEmpty()) {
            return counts;
        }
        List<BotTaskStep> steps = stepMapper.selectList(new LambdaQueryWrapper<BotTaskStep>()
                .in(BotTaskStep::getTaskId, taskIds));
        for (BotTaskStep step : steps) {
            long[] pair = counts.computeIfAbsent(step.getTaskId(), key -> new long[]{0, 0});
            pair[1]++;
            if (BotTaskStep.STATUS_COMPLETED.equals(step.getStatus())) {
                pair[0]++;
            }
        }
        return counts;
    }

    private static TaskView toView(BotTask task, long[] progress) {
        return new TaskView(task.getId(), task.getUserId(), task.getMessageType(), task.getGroupId(),
                task.getTitle(), task.getStatus(), task.getPauseReason(),
                task.getCreatedAt(), task.getUpdatedAt(), task.getCompletedAt(),
                progress[0], progress[1]);
    }

    private static TaskStepView toStepView(BotTaskStep step) {
        return new TaskStepView(step.getId(), step.getTaskId(), step.getStepNo(), step.getContent(),
                step.getStatus(), step.getAttempts(), step.getCreatedAt(), step.getCompletedAt());
    }
}
