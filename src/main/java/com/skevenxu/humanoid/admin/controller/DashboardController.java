package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public Map<String, Object> stats() {
        return dashboardService.stats();
    }

    @GetMapping("/llbot")
    public Map<String, Object> llbot() {
        return dashboardService.llbotStatus();
    }
}
