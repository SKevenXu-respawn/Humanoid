package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.RuntimeView;
import com.skevenxu.humanoid.admin.service.AdminRuntimeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/runtime")
public class RuntimeAdminController {

    private final AdminRuntimeService runtimeService;

    public RuntimeAdminController(AdminRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @GetMapping
    public RuntimeView runtime() {
        return runtimeService.snapshot();
    }
}
