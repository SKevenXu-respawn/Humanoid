package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skevenxu.humanoid.admin.dto.AdminDtos.FilteredFriendRequestView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.FriendRequestView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.FriendView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.entity.BotFriendRequest;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotFriendRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;

/** 好友管理：好友列表、删除好友、处理好友申请（全部通过 LLBot 接口）。 */
@Service
public class AdminFriendService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DELETE_FRIEND_NOTICE =
            "人机已重构功能，需要重新整理一下好友列表，所以需要删除好友，若有需求于该机器人，请向1990614908申请或者重新添加好友";

    private final LlbotApiClient llbotApiClient;
    private final BotFriendRequestMapper friendRequestMapper;

    public AdminFriendService(LlbotApiClient llbotApiClient, BotFriendRequestMapper friendRequestMapper) {
        this.llbotApiClient = llbotApiClient;
        this.friendRequestMapper = friendRequestMapper;
    }

    public List<FriendView> list(String keyword) {
        List<JsonObject> friends = llbotApiClient.getFriendList()
                .orElseThrow(() -> new IllegalStateException("获取好友列表失败，请确认 QQ 已登录"));
        String q = keyword == null ? "" : keyword.trim().toLowerCase();
        return friends.stream()
                .map(AdminFriendService::toFriendView)
                .filter(friend -> q.isEmpty()
                        || String.valueOf(friend.userId()).contains(q)
                        || contains(friend.nickname(), q)
                        || contains(friend.remark(), q)
                        || contains(friend.qid(), q))
                .toList();
    }

    public void delete(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 不合法");
        }
        if (llbotApiClient.sendPrivate(userId, DELETE_FRIEND_NOTICE).isEmpty()) {
            throw new IllegalStateException("删除前向好友发送通知失败，已取消删除，请确认 QQ 已登录且对方仍是好友");
        }
        if (!llbotApiClient.deleteFriend(userId)) {
            throw new IllegalStateException("通知已发送，但删除好友失败，请稍后重试");
        }
    }

    public PageResponse<FriendRequestView> requests(Integer page, Integer size, String status) {
        LambdaQueryWrapper<BotFriendRequest> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            query.eq(BotFriendRequest::getStatus, status.trim().toUpperCase());
        }
        query.orderByDesc(BotFriendRequest::getId);
        Page<BotFriendRequest> result = friendRequestMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getRecords().stream()
                .map(AdminFriendService::toRequestView).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public FriendRequestView approve(Long id, String remark) {
        BotFriendRequest request = getPending(id);
        String text = remark == null ? "" : remark.trim();
        if (text.length() > 64) {
            throw new IllegalArgumentException("好友备注不能超过 64 个字符");
        }
        if (!llbotApiClient.setFriendAddRequest(request.getFlag(), true, text)) {
            throw new IllegalStateException("同意好友申请失败，请确认 QQ 已登录（flag 可能已过期）");
        }
        request.setStatus(BotFriendRequest.STATUS_APPROVED);
        request.setRemark(text.isBlank() ? null : text);
        request.setHandledAt(LocalDateTime.now(ZONE));
        friendRequestMapper.updateById(request);
        return toRequestView(request);
    }

    @Transactional
    public FriendRequestView reject(Long id) {
        BotFriendRequest request = getPending(id);
        if (!llbotApiClient.setFriendAddRequest(request.getFlag(), false, "")) {
            throw new IllegalStateException("拒绝好友申请失败，请确认 QQ 已登录（flag 可能已过期）");
        }
        request.setStatus(BotFriendRequest.STATUS_REJECTED);
        request.setHandledAt(LocalDateTime.now(ZONE));
        friendRequestMapper.updateById(request);
        return toRequestView(request);
    }

    public List<FilteredFriendRequestView> doubtRequests(int count) {
        List<JsonObject> requests = llbotApiClient.getDoubtFriendRequests(count)
                .orElseThrow(() -> new IllegalStateException(
                        "获取被过滤好友申请失败，请确认 LLBot 版本 >= 6.2.0 且 QQ 已登录"));
        return requests.stream()
                .map(item -> new FilteredFriendRequestView(
                        str(item, "flag"), str(item, "uin"), str(item, "nick"),
                        str(item, "source"), str(item, "reason"), str(item, "msg"),
                        str(item, "group_code"), str(item, "time"), str(item, "type")))
                .toList();
    }

    public void approveDoubtRequest(String flag) {
        if (!StringUtils.hasText(flag)) {
            throw new IllegalArgumentException("flag 不能为空");
        }
        if (!llbotApiClient.setDoubtFriendAddRequest(flag)) {
            throw new IllegalStateException("处理被过滤好友申请失败，请确认 QQ 已登录");
        }
    }

    private BotFriendRequest getPending(Long id) {
        BotFriendRequest request = friendRequestMapper.selectById(id);
        if (request == null) {
            throw new NoSuchElementException("好友申请不存在: " + id);
        }
        if (!BotFriendRequest.STATUS_PENDING.equals(request.getStatus())) {
            throw new IllegalStateException("该好友申请已处理");
        }
        return request;
    }

    private static FriendView toFriendView(JsonObject friend) {
        long userId = longVal(friend, "user_id");
        return new FriendView(userId,
                str(friend, "nickname"), str(friend, "remark"), str(friend, "sex"),
                intVal(friend, "age"), str(friend, "qid"), str(friend, "long_nick"));
    }

    private static FriendRequestView toRequestView(BotFriendRequest request) {
        return new FriendRequestView(request.getId(), request.getUserId(), request.getComment(),
                request.getVia(), request.getStatus(), request.getRemark(), request.getEventTime(),
                request.getCreatedAt(), request.getHandledAt());
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private static String str(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static long longVal(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return 0L;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Integer intVal(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
