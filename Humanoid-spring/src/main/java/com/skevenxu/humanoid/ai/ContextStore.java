package com.skevenxu.humanoid.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.skevenxu.humanoid.entity.BotContext;
import com.skevenxu.humanoid.mapper.BotContextMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 主 AI 上下文的唯一读写入口（bot_context 表）。 */
@Service
public class ContextStore {

    private static final Gson GSON = new Gson();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotContextMapper contextMapper;

    public ContextStore(BotContextMapper contextMapper) {
        this.contextMapper = contextMapper;
    }

    public void appendUser(Long userId, String messageType, Long groupId, String content, List<String> imageRefs) {
        append(userId, messageType, groupId, BotContext.ROLE_USER, content, imageRefs);
    }

    public void appendAssistant(Long userId, String messageType, Long groupId, String content, List<String> imageRefs) {
        append(userId, messageType, groupId, BotContext.ROLE_ASSISTANT, content, imageRefs);
    }

    private void append(Long userId, String messageType, Long groupId, String role,
                        String content, List<String> imageRefs) {
        BotContext context = new BotContext();
        context.setUserId(userId);
        context.setMessageType(messageType);
        context.setGroupId(normalizeGroup(messageType, groupId));
        context.setRole(role);
        context.setContent(content);
        if (imageRefs != null && !imageRefs.isEmpty()) {
            context.setImageRefs(GSON.toJson(imageRefs));
        }
        context.setCreatedAt(LocalDateTime.now(ZONE));
        contextMapper.insert(context);
    }

    /** 最近 limit 条上下文，按时间从旧到新。 */
    public List<BotContext> recent(Long userId, String messageType, Long groupId, int limit) {
        List<BotContext> rows = new ArrayList<>(contextMapper.selectList(
                new LambdaQueryWrapper<BotContext>()
                        .eq(BotContext::getUserId, userId)
                        .eq(BotContext::getMessageType, messageType)
                        .eq(BotContext::getGroupId, normalizeGroup(messageType, groupId))
                        .orderByDesc(BotContext::getId)
                        .last("LIMIT " + limit)));
        Collections.reverse(rows);
        return rows;
    }

    public static long normalizeGroup(String messageType, Long groupId) {
        return "group".equals(messageType) && groupId != null ? groupId : 0L;
    }
}
