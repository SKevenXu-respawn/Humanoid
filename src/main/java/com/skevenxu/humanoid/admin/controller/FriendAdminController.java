package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.FilteredFriendHandleRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.FilteredFriendRequestView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.FriendRequestHandleRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.FriendRequestView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.FriendView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.service.AdminFriendService;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/friends")
public class FriendAdminController {

    private final AdminFriendService friendService;

    public FriendAdminController(AdminFriendService friendService) {
        this.friendService = friendService;
    }

    @GetMapping
    public List<FriendView> list(@RequestParam(required = false) String keyword) {
        return friendService.list(keyword);
    }

    @DeleteMapping("/{userId}")
    public Map<String, Object> delete(@PathVariable Long userId) {
        friendService.delete(userId);
        return Map.of("ok", true);
    }

    @GetMapping("/requests")
    public PageResponse<FriendRequestView> requests(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String status) {
        return friendService.requests(AdminSupport.page(page), AdminSupport.size(size), status);
    }

    @PostMapping("/requests/{id}/approve")
    public FriendRequestView approve(@PathVariable Long id,
                                     @RequestBody(required = false) FriendRequestHandleRequest request) {
        return friendService.approve(id, request == null ? null : request.remark());
    }

    @PostMapping("/requests/{id}/reject")
    public FriendRequestView reject(@PathVariable Long id) {
        return friendService.reject(id);
    }

    @GetMapping("/doubt-requests")
    public List<FilteredFriendRequestView> doubtRequests(@RequestParam(required = false) Integer count) {
        return friendService.doubtRequests(count == null ? 50 : count);
    }

    @PostMapping("/doubt-requests/approve")
    public Map<String, Object> approveDoubt(@RequestBody FilteredFriendHandleRequest request) {
        friendService.approveDoubtRequest(request == null ? null : request.flag());
        return Map.of("ok", true);
    }
}
