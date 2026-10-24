package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.dto.AdminDtos.ReminderCreateRequest;
import com.skevenxu.humanoid.admin.service.AdminReminderService;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import com.skevenxu.humanoid.entity.BotReminder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reminders")
public class ReminderAdminController {

    private final AdminReminderService reminderService;

    public ReminderAdminController(AdminReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @GetMapping
    public PageResponse<BotReminder> list(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long userId,
                                          @RequestParam(required = false) String messageType,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        return reminderService.page(AdminSupport.page(page), AdminSupport.size(size),
                status, userId, messageType,
                AdminSupport.parseDateTime(from), AdminSupport.parseDateTime(to));
    }

    @PostMapping
    public BotReminder create(@RequestBody ReminderCreateRequest request) {
        return reminderService.create(request);
    }

    @PostMapping("/{id}/cancel")
    public BotReminder cancel(@PathVariable Long id) {
        return reminderService.cancel(id);
    }

    @PostMapping("/{id}/retry")
    public BotReminder retry(@PathVariable Long id) {
        return reminderService.retry(id);
    }
}
