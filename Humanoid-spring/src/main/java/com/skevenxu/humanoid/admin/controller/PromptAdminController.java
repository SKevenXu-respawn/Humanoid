package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.PromptUpdateRequest;
import com.skevenxu.humanoid.ai.prompt.PromptService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/prompts")
public class PromptAdminController {

    private final PromptService promptService;

    public PromptAdminController(PromptService promptService) {
        this.promptService = promptService;
    }

    @GetMapping
    public List<PromptService.PromptInfo> list() {
        return promptService.list();
    }

    @PutMapping("/{key}")
    public PromptService.PromptInfo save(@PathVariable String key,
                                         @RequestBody PromptUpdateRequest request) {
        return promptService.save(key, request == null ? null : request.content());
    }

    @DeleteMapping("/{key}")
    public PromptService.PromptInfo reset(@PathVariable String key) {
        return promptService.reset(key);
    }
}
