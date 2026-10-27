package com.skevenxu.humanoid.ai.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skevenxu.humanoid.ai.DeepSeekClient;
import com.skevenxu.humanoid.ai.PromptSanitizer;
import com.skevenxu.humanoid.ai.prompt.PromptService;
import com.skevenxu.humanoid.entity.BotMemory;
import com.skevenxu.humanoid.entity.BotMemorySummary;
import com.skevenxu.humanoid.mapper.BotMemoryMapper;
import com.skevenxu.humanoid.mapper.BotMemorySummaryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 定期把旧的长期记忆压缩成高密度总结，原始记忆标记为 SUMMARIZED。 */
@Service
public class MemorySummaryService {

    private static final Logger log = LoggerFactory.getLogger(MemorySummaryService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotMemoryMapper memoryMapper;
    private final BotMemorySummaryMapper summaryMapper;
    private final MemoryService memoryService;
    private final DeepSeekClient deepSeekClient;
    private final PromptService promptService;
    private final boolean enabled;
    private final int minMemories;
    private final int maxAgeDays;
    private final int maxScopes;
    private final int maxMemoriesPerScope;

    public MemorySummaryService(BotMemoryMapper memoryMapper,
                                BotMemorySummaryMapper summaryMapper,
                                MemoryService memoryService,
                                DeepSeekClient deepSeekClient,
                                PromptService promptService,
                                @Value("${humanoid.memory.summary.enabled:true}") boolean enabled,
                                @Value("${humanoid.memory.summary.min-memories:10}") int minMemories,
                                @Value("${humanoid.memory.summary.max-age-days:7}") int maxAgeDays,
                                @Value("${humanoid.memory.summary.max-scopes-per-run:20}") int maxScopes,
                                @Value("${humanoid.memory.summary.max-memories-per-scope:200}")
                                int maxMemoriesPerScope) {
        this.memoryMapper = memoryMapper;
        this.summaryMapper = summaryMapper;
        this.memoryService = memoryService;
        this.deepSeekClient = deepSeekClient;
        this.promptService = promptService;
        this.enabled = enabled;
        this.minMemories = Math.max(2, minMemories);
        this.maxAgeDays = Math.max(1, maxAgeDays);
        this.maxScopes = Math.max(1, Math.min(100, maxScopes));
        this.maxMemoriesPerScope = Math.max(10, Math.min(1000, maxMemoriesPerScope));
    }

    @Scheduled(cron = "${humanoid.memory.summary.cron:0 30 3 * * *}")
    public void summarize() {
        runSummaries();
    }

    /** 立即执行一次总结，返回成功处理的作用域数量（管理端手动触发）。 */
    public int runSummaries() {
        if (!enabled) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now(ZONE).minusDays(maxAgeDays);
        QueryWrapper<BotMemory> query = new QueryWrapper<>();
        query.select("scope_type", "scope_id", "owner_user_id", "COUNT(*) AS cnt")
                .eq("status", BotMemory.STATUS_ACTIVE)
                .lt("updated_at", cutoff)
                .in("scope_type", List.of(BotMemory.SCOPE_USER, BotMemory.SCOPE_GROUP))
                .groupBy("scope_type", "scope_id", "owner_user_id")
                .having("COUNT(*) >= {0}", minMemories)
                .last("LIMIT " + maxScopes);
        List<Map<String, Object>> scopes = memoryMapper.selectMaps(query);
        log.info("记忆总结扫描触发, 待处理作用域={}", scopes.size());

        int summarized = 0;
        for (Map<String, Object> scope : scopes) {
            try {
                if (summarizeScope(scope)) {
                    summarized++;
                }
            } catch (Exception e) {
                log.error("记忆总结失败 scope={}", scope, e);
            }
        }
        log.info("记忆总结完成, 成功作用域={}", summarized);
        return summarized;
    }

    private boolean summarizeScope(Map<String, Object> scope) {
        String scopeType = String.valueOf(scope.get("scope_type"));
        long scopeId = number(scope.get("scope_id"));
        long owner = number(scope.get("owner_user_id"));

        List<BotMemory> memories = memoryMapper.selectList(new LambdaQueryWrapper<BotMemory>()
                .eq(BotMemory::getScopeType, scopeType)
                .eq(BotMemory::getScopeId, scopeId)
                .eq(BotMemory::getOwnerUserId, owner)
                .eq(BotMemory::getStatus, BotMemory.STATUS_ACTIVE)
                .lt(BotMemory::getUpdatedAt, LocalDateTime.now(ZONE).minusDays(maxAgeDays))
                .orderByAsc(BotMemory::getId)
                .last("LIMIT " + maxMemoriesPerScope));
        if (memories.isEmpty()) {
            return false;
        }

        BotMemorySummary existing = memoryService.findSummary(scopeType, scopeId, owner);
        String prior = existing == null || existing.getSummary() == null ? "无" : existing.getSummary();
        StringBuilder transcript = new StringBuilder();
        for (BotMemory memory : memories) {
            transcript.append("- ").append(PromptSanitizer.clean(memory.getContent())).append("\n");
        }

        String userContent = "已有总结：\n" + PromptSanitizer.clean(prior)
                + "\n\n需要合并的记忆：\n" + transcript;
        Optional<String> result = deepSeekClient.chat(promptService.memorySummaryPrompt(),
                List.of(DeepSeekClient.ChatMessage.user(userContent, List.of())));
        if (result.isEmpty() || result.get().isBlank()) {
            log.warn("记忆总结未返回内容 scope={}/{} owner={}", scopeType, scopeId, owner);
            return false;
        }

        LocalDateTime now = LocalDateTime.now(ZONE);
        if (existing == null) {
            existing = new BotMemorySummary();
            existing.setScopeType(scopeType);
            existing.setScopeId(scopeId);
            existing.setOwnerUserId(owner);
            existing.setSummary(result.get().trim());
            existing.setMemoryCount(memories.size());
            existing.setUpdatedAt(now);
            summaryMapper.insert(existing);
        } else {
            existing.setSummary(result.get().trim());
            existing.setMemoryCount((existing.getMemoryCount() == null ? 0 : existing.getMemoryCount())
                    + memories.size());
            existing.setUpdatedAt(now);
            summaryMapper.updateById(existing);
        }
        memoryService.markSummarized(memories.stream().map(BotMemory::getId).toList());
        log.info("记忆总结已更新 scope={}/{} owner={} rows={} chars={}",
                scopeType, scopeId, owner, memories.size(), existing.getSummary().length());
        return true;
    }

    private static long number(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
