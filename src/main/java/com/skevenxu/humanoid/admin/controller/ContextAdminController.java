package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.ContextMessageUpdateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.ContextMessageView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.ContextScopeView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.dto.AdminDtos.SummaryUpdateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.SummaryView;
import com.skevenxu.humanoid.admin.service.AdminContextService;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/admin")
public class ContextAdminController {

    private final AdminContextService contextService;

    public ContextAdminController(AdminContextService contextService) {
        this.contextService = contextService;
    }

    @GetMapping("/contexts")
    public List<ContextScopeView> scopes(@RequestParam(required = false) String q) {
        return contextService.scopes(q);
    }

    @GetMapping("/contexts/messages")
    public PageResponse<ContextMessageView> messages(@RequestParam Long userId,
                                                     @RequestParam(required = false) String messageType,
                                                     @RequestParam(required = false) Long groupId,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return contextService.messages(userId, messageType, groupId,
                AdminSupport.page(page), AdminSupport.size(size));
    }

    @DeleteMapping("/contexts")
    public Map<String, Object> clear(@RequestParam Long userId,
                                     @RequestParam(required = false) String messageType,
                                     @RequestParam(required = false) Long groupId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return Map.of("deleted", contextService.clearContext(userId, messageType, groupId));
    }

    @PutMapping("/contexts/messages/{id}")
    public ContextMessageView updateMessage(@PathVariable Long id,
                                            @RequestBody ContextMessageUpdateRequest request) {
        return contextService.updateMessage(id, request);
    }

    @DeleteMapping("/contexts/messages/{id}")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long id) {
        contextService.deleteMessage(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/contexts/summary")
    public SummaryView summary(@RequestParam Long userId,
                               @RequestParam(required = false) String messageType,
                               @RequestParam(required = false) Long groupId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return contextService.getSummary(userId, messageType, groupId);
    }

    @PutMapping("/contexts/summary")
    public SummaryView saveSummary(@RequestBody SummaryUpdateRequest request) {
        return contextService.saveSummary(request);
    }

    @DeleteMapping("/contexts/summary")
    public ResponseEntity<Void> deleteSummary(@RequestParam Long userId,
                                              @RequestParam(required = false) String messageType,
                                              @RequestParam(required = false) Long groupId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        contextService.deleteSummary(userId, messageType, groupId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        AdminContextService.ImageData data = contextService.image(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(data.mimeType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(data.bytes());
    }
}
