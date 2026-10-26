package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.service.AdminVideoQueueService;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import com.skevenxu.humanoid.entity.BotVideoQueue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/video-queue")
public class VideoQueueAdminController {

    private final AdminVideoQueueService queueService;

    public VideoQueueAdminController(AdminVideoQueueService queueService) {
        this.queueService = queueService;
    }

    @GetMapping
    public PageResponse<BotVideoQueue> page(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Long userId) {
        return queueService.page(AdminSupport.page(page), AdminSupport.size(size), status, userId);
    }

    @PostMapping("/{id}/send")
    public BotVideoQueue send(@PathVariable Long id) {
        return queueService.sendNow(id);
    }

    @PostMapping("/{id}/skip")
    public BotVideoQueue skip(@PathVariable Long id) {
        return queueService.skip(id);
    }

    @PostMapping("/{id}/requeue")
    public BotVideoQueue requeue(@PathVariable Long id) {
        return queueService.requeue(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        queueService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
