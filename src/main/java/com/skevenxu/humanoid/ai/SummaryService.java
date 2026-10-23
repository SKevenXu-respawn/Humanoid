package com.skevenxu.humanoid.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotSummary;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotSummaryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 滚动总结：每积累 threshold 条对话，由子 AI 把重点合并进已有总结。
 * 主 AI 每次只带「系统提示词 + 总结 + 当前消息」。
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotSummaryMapper summaryMapper;
    private final BotConversationMapper conversationMapper;
    private final DeepSeekClient deepSeekClient;
    private final String summarySystemPrompt;
    private final int threshold;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public SummaryService(BotSummaryMapper summaryMapper,
                          BotConversationMapper conversationMapper,
                          DeepSeekClient deepSeekClient,
                          @Value("classpath:prompts/summary-prompt.txt") Resource summaryPrompt,
                          @Value("${humanoid.ai.summary-threshold:100}") int threshold) {
        this.summaryMapper = summaryMapper;
        this.conversationMapper = conversationMapper;
        this.deepSeekClient = deepSeekClient;
        this.summarySystemPrompt = loadPrompt(summaryPrompt);
        this.threshold = threshold;
    }

    /** 返回当前会话的最新总结；达到阈值时先合并一次。 */
    public String getSummary(BotConversation current) {
        String key = scopeKey(current);
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            BotSummary record = findOrCreate(current);
            try {
                List<BotConversation> pending = pendingBefore(current, record.getLastConversationId());
                if (pending.size() >= threshold) {
                    merge(record, pending.subList(0, threshold));
                }
            } catch (Exception e) {
                log.error("总结失败 user_id={} group_id={}", current.getUserId(), current.getGroupId(), e);
            }
            return record.getSummary() == null ? "" : record.getSummary();
        }
    }

    /** 只读取当前总结，不触发合并。 */
    public String peekSummary(Long userId, String messageType, Long groupId) {
        long scope = "group".equals(messageType) && groupId != null ? groupId : 0L;
        BotSummary record = summaryMapper.selectOne(new LambdaQueryWrapper<BotSummary>()
                .eq(BotSummary::getUserId, userId)
                .eq(BotSummary::getMessageType, messageType)
                .eq(BotSummary::getGroupId, scope));
        return record == null || record.getSummary() == null ? "" : record.getSummary();
    }

    private BotSummary findOrCreate(BotConversation current) {
        long scopeGroup = scopeGroup(current);
        BotSummary record = summaryMapper.selectOne(new LambdaQueryWrapper<BotSummary>()
                .eq(BotSummary::getUserId, current.getUserId())
                .eq(BotSummary::getMessageType, current.getMessageType())
                .eq(BotSummary::getGroupId, scopeGroup));
        if (record != null) {
            return record;
        }
        record = new BotSummary();
        record.setUserId(current.getUserId());
        record.setMessageType(current.getMessageType());
        record.setGroupId(scopeGroup);
        record.setSummary("");
        record.setLastConversationId(0L);
        record.setCreatedAt(LocalDateTime.now(ZONE));
        summaryMapper.insert(record);
        return record;
    }

    /** 当前消息之前、尚未被总结的对话，按 id 升序最多取 threshold 条。 */
    private List<BotConversation> pendingBefore(BotConversation current, Long lastConversationId) {
        LambdaQueryWrapper<BotConversation> query = new LambdaQueryWrapper<BotConversation>()
                .eq(BotConversation::getUserId, current.getUserId())
                .eq(BotConversation::getMessageType, current.getMessageType())
                .gt(BotConversation::getId, lastConversationId == null ? 0L : lastConversationId)
                .lt(BotConversation::getId, current.getId())
                .orderByAsc(BotConversation::getId)
                .last("LIMIT " + threshold);
        if ("group".equals(current.getMessageType())) {
            query.eq(BotConversation::getGroupId, current.getGroupId());
        } else {
            query.isNull(BotConversation::getGroupId);
        }
        return conversationMapper.selectList(query);
    }

    private void merge(BotSummary record, List<BotConversation> window) {
        String transcript = window.stream()
                .map(row -> ("bot".equals(row.getRole()) ? "助手: " : "用户: ") + PromptSanitizer.clean(textOf(row)))
                .collect(Collectors.joining("\n"));
        String userContent = "以前的总结为：\n"
                + PromptSanitizer.clean(record.getSummary() == null || record.getSummary().isBlank()
                        ? "无" : record.getSummary())
                + "\n\n以下是需要合并的对话：\n" + transcript;

        Optional<String> merged = deepSeekClient.chat(summarySystemPrompt,
                List.of(DeepSeekClient.ChatMessage.user(userContent, List.of())));
        if (merged.isEmpty() || merged.get().isBlank()) {
            log.warn("子 AI 未返回总结 user_id={} group_id={}",
                    record.getUserId(), record.getGroupId());
            return;
        }

        record.setSummary(merged.get().trim());
        record.setLastConversationId(window.get(window.size() - 1).getId());
        summaryMapper.updateById(record);
        log.info("已更新总结 user_id={} group_id={} rows={} summary_chars={}",
                record.getUserId(), record.getGroupId(), window.size(), record.getSummary().length());
    }

    private static String textOf(BotConversation row) {
        return row.getContent() == null || row.getContent().isBlank() ? "[图片]" : row.getContent();
    }

    private static long scopeGroup(BotConversation current) {
        return "group".equals(current.getMessageType()) && current.getGroupId() != null
                ? current.getGroupId()
                : 0L;
    }

    private static String scopeKey(BotConversation current) {
        return current.getUserId() + ":" + current.getMessageType() + ":" + scopeGroup(current);
    }

    private static String loadPrompt(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 prompts/summary-prompt.txt", e);
        }
    }
}
