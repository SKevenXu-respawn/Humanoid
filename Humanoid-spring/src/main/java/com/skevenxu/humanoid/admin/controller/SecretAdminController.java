package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.SecretUpdateRequest;
import com.skevenxu.humanoid.ai.secret.SecretService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/secrets")
public class SecretAdminController {

    private final SecretService secretService;

    public SecretAdminController(SecretService secretService) {
        this.secretService = secretService;
    }

    @GetMapping
    public List<SecretService.SecretInfo> list() {
        return secretService.list();
    }

    @PutMapping("/{key}")
    public SecretService.SecretInfo save(@PathVariable String key,
                                         @RequestBody SecretUpdateRequest request) {
        return secretService.save(key, request == null ? null : request.value());
    }

    @DeleteMapping("/{key}")
    public SecretService.SecretInfo clear(@PathVariable String key) {
        return secretService.clear(key);
    }
}
