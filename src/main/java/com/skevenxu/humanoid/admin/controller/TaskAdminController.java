package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PauseRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.StepCreateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.TaskCreateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.TaskDetailView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.TaskView;
import com.skevenxu.humanoid.admin.service.AdminTaskService;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/tasks")
public class TaskAdminController {

    private final AdminTaskService taskService;

    public TaskAdminController(AdminTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public PageResponse<TaskView> list(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Long userId,
                                       @RequestParam(required = false) String messageType,
                                       @RequestParam(required = false) Long groupId,
                                       @RequestParam(required = false) String keyword) {
        return taskService.page(AdminSupport.page(page), AdminSupport.size(size),
                status, userId, messageType, groupId, keyword);
    }

    @PostMapping
    public TaskDetailView create(@RequestBody TaskCreateRequest request) {
        return taskService.create(request);
    }

    @GetMapping("/{id}")
    public TaskDetailView detail(@PathVariable Long id) {
        return taskService.detail(id);
    }

    @PostMapping("/{id}/steps")
    public TaskDetailView addStep(@PathVariable Long id, @RequestBody StepCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        return taskService.addStep(id, request.content());
    }

    @PostMapping("/{id}/steps/{stepId}/complete")
    public TaskDetailView completeStep(@PathVariable Long id, @PathVariable Long stepId) {
        return taskService.completeStep(id, stepId);
    }

    @PostMapping("/{id}/start")
    public TaskDetailView start(@PathVariable Long id) {
        return taskService.start(id);
    }

    @PostMapping("/{id}/resume")
    public TaskDetailView resume(@PathVariable Long id) {
        return taskService.resume(id);
    }

    @PostMapping("/{id}/pause")
    public TaskDetailView pause(@PathVariable Long id, @RequestBody PauseRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        return taskService.pause(id, request.reason());
    }

    @PostMapping("/{id}/complete")
    public TaskDetailView complete(@PathVariable Long id) {
        return taskService.complete(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
