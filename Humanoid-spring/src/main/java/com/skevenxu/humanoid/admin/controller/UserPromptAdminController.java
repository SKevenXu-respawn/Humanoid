package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.UserPromptUpdateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.UserPromptView;
import com.skevenxu.humanoid.admin.service.AdminUserPromptService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/user-prompts")
public class UserPromptAdminController {

    private final AdminUserPromptService userPromptService;

    public UserPromptAdminController(AdminUserPromptService userPromptService) {
        this.userPromptService = userPromptService;
    }

    @GetMapping
    public List<UserPromptView> list(@RequestParam(required = false) String q) {
        return userPromptService.list(q);
    }

    @PutMapping("/{userId}")
    public UserPromptView save(@PathVariable Long userId,
                               @RequestBody UserPromptUpdateRequest request) {
        return userPromptService.save(userId, request == null ? null : request.content());
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> delete(@PathVariable Long userId) {
        userPromptService.delete(userId);
        return ResponseEntity.noContent().build();
    }
}
