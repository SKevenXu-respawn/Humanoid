package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.dto.AdminDtos.ReminderCreateRequest;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import com.skevenxu.humanoid.entity.BotReminder;
import com.skevenxu.humanoid.mapper.BotReminderMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.NoSuchElementException;

/** 提醒管理。 */
@Service
public class AdminReminderService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotReminderMapper reminderMapper;

    public AdminReminderService(BotReminderMapper reminderMapper) {
        this.reminderMapper = reminderMapper;
    }

    public PageResponse<BotReminder> page(Integer page, Integer size, String status, Long userId,
                                          String messageType, LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<BotReminder> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            query.eq(BotReminder::getStatus, status.trim().toUpperCase());
        }
        if (userId != null) {
            query.eq(BotReminder::getUserId, userId);
        }
        if (StringUtils.hasText(messageType)) {
            query.eq(BotReminder::getMessageType, AdminSupport.messageType(messageType));
        }
        if (from != null) {
            query.ge(BotReminder::getRemindAt, from);
        }
        if (to != null) {
            query.le(BotReminder::getRemindAt, to);
        }
        query.orderByDesc(BotReminder::getId);
        Page<BotReminder> result = reminderMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(),
                result.getCurrent(), result.getSize());
    }

    public BotReminder create(ReminderCreateRequest request) {
        if (request == null || request.userId() == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (request.remindAt() == null) {
            throw new IllegalArgumentException("remindAt 不能为空");
        }
        String type = AdminSupport.messageType(request.messageType());
        String content = AdminSupport.requireText(request.content(), 1000, "提示词");
        if (!request.remindAt().isAfter(LocalDateTime.now(ZONE))) {
            throw new IllegalArgumentException("提醒时间必须是将来的时间");
        }

        BotReminder reminder = new BotReminder();
        reminder.setUserId(request.userId());
        reminder.setMessageType(type);
        reminder.setGroupId(AdminSupport.scopeGroup(type, request.groupId()));
        reminder.setContent(content);
        reminder.setRemindAt(request.remindAt());
        reminder.setStatus(BotReminder.STATUS_PENDING);
        reminder.setAttempts(0);
        reminder.setCreatedAt(LocalDateTime.now(ZONE));
        reminderMapper.insert(reminder);
        return reminder;
    }

    public BotReminder cancel(Long id) {
        BotReminder reminder = get(id);
        if (!BotReminder.STATUS_PENDING.equals(reminder.getStatus())) {
            throw new IllegalStateException("只有待发送的提醒可以取消");
        }
        reminder.setStatus(BotReminder.STATUS_CANCELLED);
        reminderMapper.updateById(reminder);
        return reminder;
    }

    public BotReminder retry(Long id) {
        BotReminder reminder = get(id);
        if (!BotReminder.STATUS_FAILED.equals(reminder.getStatus())
                && !BotReminder.STATUS_CANCELLED.equals(reminder.getStatus())) {
            throw new IllegalStateException("只有失败或已取消的提醒可以重试");
        }
        reminder.setStatus(BotReminder.STATUS_PENDING);
        reminder.setAttempts(0);
        reminder.setRemindAt(LocalDateTime.now(ZONE));
        reminder.setSentAt(null);
        reminderMapper.updateById(reminder);
        return reminder;
    }

    private BotReminder get(Long id) {
        BotReminder reminder = reminderMapper.selectById(id);
        if (reminder == null) {
            throw new NoSuchElementException("提醒不存在: " + id);
        }
        return reminder;
    }
}
