package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.MemorySummaryUpdateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.MemoryUpdateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.service.AdminMemoryService;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import com.skevenxu.humanoid.ai.memory.MemorySummaryService;
import com.skevenxu.humanoid.entity.BotMemory;
import com.skevenxu.humanoid.entity.BotMemorySummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/memories")
public class MemoryAdminController {

    private final AdminMemoryService memoryService;
    private final MemorySummaryService summaryService;

    public MemoryAdminController(AdminMemoryService memoryService,
                                 MemorySummaryService summaryService) {
        this.memoryService = memoryService;
        this.summaryService = summaryService;
    }

    @GetMapping
    public PageResponse<BotMemory> page(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String memoryType,
                                        @RequestParam(required = false) Long userId,
                                        @RequestParam(required = false) String q) {
        return memoryService.page(AdminSupport.page(page), AdminSupport.size(size),
                status, memoryType, userId, q);
    }

    @PutMapping("/{id}")
    public BotMemory update(@PathVariable Long id, @RequestBody MemoryUpdateRequest request) {
        return memoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        memoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summaries")
    public PageResponse<BotMemorySummary> summaries(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String scopeType,
                                                    @RequestParam(required = false) Long scopeId,
                                                    @RequestParam(required = false) Long ownerUserId) {
        return memoryService.summaries(AdminSupport.page(page), AdminSupport.size(size),
                scopeType, scopeId, ownerUserId);
    }

    @PutMapping("/summaries/{id}")
    public BotMemorySummary updateSummary(@PathVariable Long id,
                                          @RequestBody MemorySummaryUpdateRequest request) {
        return memoryService.updateSummary(id, request);
    }

    @DeleteMapping("/summaries/{id}")
    public ResponseEntity<Void> deleteSummary(@PathVariable Long id) {
        memoryService.deleteSummary(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/summaries/run")
    public Map<String, Object> runSummary() {
        return Map.of("summarized", summaryService.runSummaries());
    }
}
