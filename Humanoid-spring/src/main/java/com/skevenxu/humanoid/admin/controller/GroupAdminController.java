package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.EnabledRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.GroupRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.service.AdminGroupService;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import com.skevenxu.humanoid.entity.BotGroup;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/groups")
public class GroupAdminController {

    private final AdminGroupService groupService;

    public GroupAdminController(AdminGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public PageResponse<BotGroup> list(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Boolean enabled) {
        return groupService.page(AdminSupport.page(page), AdminSupport.size(size), keyword, enabled);
    }

    @PostMapping
    public ResponseEntity<BotGroup> create(@RequestBody GroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.create(request));
    }

    @PutMapping("/{id}")
    public BotGroup update(@PathVariable Long id, @RequestBody GroupRequest request) {
        return groupService.update(id, request);
    }

    @PatchMapping("/{id}/enabled")
    public BotGroup setEnabled(@PathVariable Long id, @RequestBody EnabledRequest request) {
        if (request == null || request.enabled() == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        return groupService.setEnabled(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        groupService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
