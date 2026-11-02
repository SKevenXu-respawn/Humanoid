package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.EnabledRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.dto.AdminDtos.UserRequest;
import com.skevenxu.humanoid.admin.service.AdminUserService;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import com.skevenxu.humanoid.entity.BotUser;
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
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final AdminUserService userService;

    public UserAdminController(AdminUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public PageResponse<BotUser> list(@RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Boolean enabled) {
        return userService.page(AdminSupport.page(page), AdminSupport.size(size), keyword, enabled);
    }

    @PostMapping
    public ResponseEntity<BotUser> create(@RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @PutMapping("/{id}")
    public BotUser update(@PathVariable Long id, @RequestBody UserRequest request) {
        return userService.update(id, request);
    }

    @PatchMapping("/{id}/enabled")
    public BotUser setEnabled(@PathVariable Long id, @RequestBody EnabledRequest request) {
        if (request == null || request.enabled() == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        return userService.setEnabled(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
