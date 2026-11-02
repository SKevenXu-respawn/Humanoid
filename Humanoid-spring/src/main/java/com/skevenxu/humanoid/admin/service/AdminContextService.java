package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.skevenxu.humanoid.admin.dto.AdminDtos.ContextMessageUpdateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.ContextMessageView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.ContextScopeView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.dto.AdminDtos.SummaryUpdateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.SummaryView;
import com.skevenxu.humanoid.admin.support.AdminSupport;
import com.skevenxu.humanoid.ai.ImageDataLoader;
import com.skevenxu.humanoid.entity.BotContext;
import com.skevenxu.humanoid.entity.BotImage;
import com.skevenxu.humanoid.entity.BotSummary;
import com.skevenxu.humanoid.mapper.BotContextMapper;
import com.skevenxu.humanoid.mapper.BotImageMapper;
import com.skevenxu.humanoid.mapper.BotSummaryMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** 上下文、总结和图片管理。 */
@Service
public class AdminContextService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String IMAGE_PREFIX = "image:";

    private final BotContextMapper contextMapper;
    private final BotSummaryMapper summaryMapper;
    private final BotImageMapper imageMapper;

    public AdminContextService(BotContextMapper contextMapper,
                               BotSummaryMapper summaryMapper,
                               BotImageMapper imageMapper) {
        this.contextMapper = contextMapper;
        this.summaryMapper = summaryMapper;
        this.imageMapper = imageMapper;
    }

    public List<ContextScopeView> scopes(String q) {
        QueryWrapper<BotContext> query = new QueryWrapper<>();
        query.select("user_id", "message_type", "group_id",
                        "COUNT(*) AS message_count", "MAX(created_at) AS last_at")
                .groupBy("user_id", "message_type", "group_id")
                .orderByDesc("MAX(created_at)");
        List<Map<String, Object>> rows = contextMapper.selectMaps(query);

        Map<String, BotSummary> summaries = new HashMap<>();
        for (BotSummary summary : summaryMapper.selectList(null)) {
            summaries.put(key(summary.getUserId(), summary.getMessageType(), summary.getGroupId()), summary);
        }

        String filter = q == null ? "" : q.trim().toLowerCase();
        List<ContextScopeView> views = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long userId = number(row.get("user_id"));
            String messageType = String.valueOf(row.get("message_type"));
            Long groupId = number(row.get("group_id"));
            if (!filter.isEmpty()
                    && !String.valueOf(userId).contains(filter)
                    && !String.valueOf(groupId).contains(filter)
                    && !messageType.contains(filter)) {
                continue;
            }
            long count = number(row.get("message_count")).longValue();
            LocalDateTime lastAt = date(row.get("last_at"));
            BotSummary summary = summaries.get(key(userId, messageType, groupId));
            views.add(new ContextScopeView(userId, messageType,
                    "group".equals(messageType) ? groupId : 0L,
                    count, lastAt,
                    summary == null || summary.getSummary() == null ? 0 : summary.getSummary().length(),
                    summary == null ? null : summary.getLastConversationId()));
        }
        return views;
    }

    public PageResponse<ContextMessageView> messages(Long userId, String messageType, Long groupId,
                                                     Integer page, Integer size) {
        String type = AdminSupport.messageType(messageType);
        LambdaQueryWrapper<BotContext> query = new LambdaQueryWrapper<BotContext>()
                .eq(BotContext::getUserId, userId)
                .eq(BotContext::getMessageType, type)
                .eq(BotContext::getGroupId, AdminSupport.scopeGroup(type, groupId))
                .orderByDesc(BotContext::getId);
        Page<BotContext> result = contextMapper.selectPage(new Page<>(page, size), query);
        List<ContextMessageView> views = result.getRecords().stream()
                .map(AdminContextService::toMessageView)
                .toList();
        return new PageResponse<>(views, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 修改单条上下文消息的文本；图片引用和角色保持不变。 */
    public ContextMessageView updateMessage(Long id, ContextMessageUpdateRequest request) {
        BotContext row = contextMapper.selectById(id);
        if (row == null) {
            throw new NoSuchElementException("上下文消息不存在: " + id);
        }
        if (request == null || request.content() == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        String text = request.content().trim();
        if (text.length() > 20000) {
            throw new IllegalArgumentException("内容过长");
        }
        row.setContent(text);
        contextMapper.updateById(row);
        return toMessageView(row);
    }

    public void deleteMessage(Long id) {
        BotContext row = contextMapper.selectById(id);
        if (row == null) {
            throw new NoSuchElementException("上下文消息不存在: " + id);
        }
        contextMapper.deleteById(id);
    }

    private static ContextMessageView toMessageView(BotContext row) {
        List<String> refs = imageRefs(row.getImageRefs());
        List<Long> ids = refs.stream()
                .filter(ref -> ref.startsWith(IMAGE_PREFIX))
                .map(ref -> {
                    try {
                        return Long.parseLong(ref.substring(IMAGE_PREFIX.length()));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return new ContextMessageView(row.getId(), row.getRole(), row.getContent(),
                refs, ids, row.getCreatedAt());
    }

    public int clearContext(Long userId, String messageType, Long groupId) {
        String type = AdminSupport.messageType(messageType);
        return contextMapper.delete(new LambdaQueryWrapper<BotContext>()
                .eq(BotContext::getUserId, userId)
                .eq(BotContext::getMessageType, type)
                .eq(BotContext::getGroupId, AdminSupport.scopeGroup(type, groupId)));
    }

    public SummaryView getSummary(Long userId, String messageType, Long groupId) {
        String type = AdminSupport.messageType(messageType);
        BotSummary summary = findSummary(userId, type, AdminSupport.scopeGroup(type, groupId));
        return summary == null ? null : toView(summary);
    }

    public SummaryView saveSummary(SummaryUpdateRequest request) {
        if (request == null || request.userId() == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String type = AdminSupport.messageType(request.messageType());
        long group = AdminSupport.scopeGroup(type, request.groupId());
        String text = request.summary() == null ? "" : request.summary().trim();
        if (text.length() > 100000) {
            throw new IllegalArgumentException("总结过长");
        }

        BotSummary summary = findSummary(request.userId(), type, group);
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (summary == null) {
            summary = new BotSummary();
            summary.setUserId(request.userId());
            summary.setMessageType(type);
            summary.setGroupId(group);
            summary.setSummary(text);
            summary.setLastConversationId(request.lastConversationId() == null ? 0L : request.lastConversationId());
            summary.setCreatedAt(now);
            summary.setUpdatedAt(now);
            summaryMapper.insert(summary);
        } else {
            summary.setSummary(text);
            if (request.lastConversationId() != null) {
                summary.setLastConversationId(request.lastConversationId());
            }
            summary.setUpdatedAt(now);
            summaryMapper.updateById(summary);
        }
        return toView(summary);
    }

    public void deleteSummary(Long userId, String messageType, Long groupId) {
        String type = AdminSupport.messageType(messageType);
        summaryMapper.delete(new LambdaQueryWrapper<BotSummary>()
                .eq(BotSummary::getUserId, userId)
                .eq(BotSummary::getMessageType, type)
                .eq(BotSummary::getGroupId, AdminSupport.scopeGroup(type, groupId)));
    }

    public record ImageData(byte[] bytes, String mimeType) {
    }

    public ImageData image(Long id) {
        BotImage image = imageMapper.selectById(id);
        if (image == null || image.getDataUrl() == null) {
            throw new NoSuchElementException("图片不存在: " + id);
        }
        String dataUrl = image.getDataUrl();
        String mimeType = "image/png";
        String base64;
        if (dataUrl.startsWith("data:")) {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) {
                throw new NoSuchElementException("图片数据无效: " + id);
            }
            String meta = dataUrl.substring(5, comma);
            int semi = meta.indexOf(';');
            if (semi > 0) {
                mimeType = meta.substring(0, semi);
            }
            base64 = dataUrl.substring(comma + 1);
        } else {
            base64 = dataUrl;
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (!mimeType.startsWith("image/")) {
            mimeType = ImageDataLoader.detectMime(bytes);
        }
        return new ImageData(bytes, mimeType);
    }

    private BotSummary findSummary(Long userId, String messageType, long groupId) {
        return summaryMapper.selectOne(new LambdaQueryWrapper<BotSummary>()
                .eq(BotSummary::getUserId, userId)
                .eq(BotSummary::getMessageType, messageType)
                .eq(BotSummary::getGroupId, groupId));
    }

    private static SummaryView toView(BotSummary summary) {
        return new SummaryView(summary.getId(), summary.getSummary(),
                summary.getLastConversationId(), summary.getUpdatedAt());
    }

    private static List<String> imageRefs(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return List.of();
            }
            JsonArray array = root.getAsJsonArray();
            List<String> refs = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (element != null && !element.isJsonNull()) {
                    refs.add(element.getAsString());
                }
            }
            return refs;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String key(Long userId, String messageType, Long groupId) {
        return userId + ":" + messageType + ":" + groupId;
    }

    private static Long number(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static LocalDateTime date(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
