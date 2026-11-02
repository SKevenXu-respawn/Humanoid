package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.admin.dto.AdminDtos.UserPromptView;
import com.skevenxu.humanoid.entity.BotUser;
import com.skevenxu.humanoid.entity.BotUserPrompt;
import com.skevenxu.humanoid.mapper.BotUserMapper;
import com.skevenxu.humanoid.mapper.BotUserPromptMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** 按 QQ 绑定的自定义系统提示词管理。 */
@Service
public class AdminUserPromptService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotUserPromptMapper promptMapper;
    private final BotUserMapper userMapper;

    public AdminUserPromptService(BotUserPromptMapper promptMapper, BotUserMapper userMapper) {
        this.promptMapper = promptMapper;
        this.userMapper = userMapper;
    }

    public List<UserPromptView> list(String q) {
        List<BotUserPrompt> rows = promptMapper.selectList(new LambdaQueryWrapper<BotUserPrompt>()
                .orderByDesc(BotUserPrompt::getUpdatedAt));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = rows.stream().map(BotUserPrompt::getUserId).toList();
        Map<Long, String> nicknames = new HashMap<>();
        for (BotUser user : userMapper.selectList(new LambdaQueryWrapper<BotUser>()
                .in(BotUser::getUserId, userIds))) {
            nicknames.put(user.getUserId(), user.getNickname());
        }
        String filter = q == null ? "" : q.trim().toLowerCase();
        return rows.stream()
                .filter(row -> filter.isEmpty()
                        || String.valueOf(row.getUserId()).contains(filter)
                        || (nicknames.get(row.getUserId()) != null
                        && nicknames.get(row.getUserId()).toLowerCase().contains(filter)))
                .map(row -> new UserPromptView(row.getUserId(), nicknames.get(row.getUserId()),
                        row.getContent(), row.getUpdatedAt()))
                .toList();
    }

    public UserPromptView save(Long userId, String content) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("QQ 号不合法");
        }
        String text = content == null ? "" : content.trim();
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("提示词不能为空；如需回退全局提示词请删除绑定");
        }
        if (text.length() > 200_000) {
            throw new IllegalArgumentException("提示词过长");
        }

        BotUserPrompt row = find(userId);
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (row == null) {
            row = new BotUserPrompt();
            row.setUserId(userId);
            row.setContent(text);
            row.setUpdatedAt(now);
            promptMapper.insert(row);
        } else {
            row.setContent(text);
            row.setUpdatedAt(now);
            promptMapper.updateById(row);
        }
        return new UserPromptView(userId, nickname(userId), row.getContent(), row.getUpdatedAt());
    }

    public void delete(Long userId) {
        BotUserPrompt row = find(userId);
        if (row == null) {
            throw new NoSuchElementException("该用户没有自定义提示词: " + userId);
        }
        promptMapper.deleteById(row.getId());
    }

    private BotUserPrompt find(Long userId) {
        if (userId == null) {
            return null;
        }
        return promptMapper.selectOne(new LambdaQueryWrapper<BotUserPrompt>()
                .eq(BotUserPrompt::getUserId, userId));
    }

    private String nickname(Long userId) {
        BotUser user = userMapper.selectOne(new LambdaQueryWrapper<BotUser>()
                .eq(BotUser::getUserId, userId));
        return user == null ? null : user.getNickname();
    }
}
