package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skevenxu.humanoid.admin.dto.AdminDtos.MemorySummaryUpdateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.MemoryUpdateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.ai.memory.MemoryService;
import com.skevenxu.humanoid.entity.BotMemory;
import com.skevenxu.humanoid.entity.BotMemorySummary;
import com.skevenxu.humanoid.mapper.BotMemoryMapper;
import com.skevenxu.humanoid.mapper.BotMemorySummaryMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.NoSuchElementException;
import java.util.Set;

/** 长期记忆管理。 */
@Service
public class AdminMemoryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> STATUSES = Set.of(
            BotMemory.STATUS_ACTIVE, BotMemory.STATUS_SUMMARIZED, BotMemory.STATUS_DELETED);

    private final BotMemoryMapper memoryMapper;
    private final BotMemorySummaryMapper summaryMapper;
    private final MemoryService memoryService;

    public AdminMemoryService(BotMemoryMapper memoryMapper,
                              BotMemorySummaryMapper summaryMapper,
                              MemoryService memoryService) {
        this.memoryMapper = memoryMapper;
        this.summaryMapper = summaryMapper;
        this.memoryService = memoryService;
    }

    public PageResponse<BotMemory> page(Integer page, Integer size, String status,
                                        String memoryType, Long userId, String q) {
        LambdaQueryWrapper<BotMemory> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            query.eq(BotMemory::getStatus, status.trim().toUpperCase());
        }
        if (StringUtils.hasText(memoryType)) {
            query.eq(BotMemory::getMemoryType, memoryType.trim().toUpperCase());
        }
        if (userId != null) {
            query.eq(BotMemory::getOwnerUserId, userId);
        }
        if (StringUtils.hasText(q)) {
            query.like(BotMemory::getContent, q.trim());
        }
        query.orderByDesc(BotMemory::getId);
        Page<BotMemory> result = memoryMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(),
                result.getCurrent(), result.getSize());
    }

    public BotMemory update(Long id, MemoryUpdateRequest request) {
        BotMemory memory = get(id);
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (request.content() != null) {
            String text = request.content().trim();
            if (text.isBlank()) {
                throw new IllegalArgumentException("content 不能为空");
            }
            if (text.length() > 2000) {
                throw new IllegalArgumentException("content 不能超过 2000 字");
            }
            memory.setContent(text);
            memory.setContentHash(MemoryService.contentHash(text));
        }
        if (request.importance() != null) {
            if (request.importance() < 1 || request.importance() > 5) {
                throw new IllegalArgumentException("importance 必须在 1-5 之间");
            }
            memory.setImportance(request.importance());
        }
        if (StringUtils.hasText(request.status())) {
            String status = request.status().trim().toUpperCase();
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException("status 不合法");
            }
            memory.setStatus(status);
        }
        memory.setUpdatedAt(LocalDateTime.now(ZONE));
        memoryMapper.updateById(memory);
        return memory;
    }

    public void delete(Long id) {
        get(id);
        memoryService.deleteMemory(id);
    }

    public PageResponse<BotMemorySummary> summaries(Integer page, Integer size,
                                                    String scopeType, Long scopeId, Long ownerUserId) {
        LambdaQueryWrapper<BotMemorySummary> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(scopeType)) {
            query.eq(BotMemorySummary::getScopeType, scopeType.trim().toUpperCase());
        }
        if (scopeId != null) {
            query.eq(BotMemorySummary::getScopeId, scopeId);
        }
        if (ownerUserId != null) {
            query.eq(BotMemorySummary::getOwnerUserId, ownerUserId);
        }
        query.orderByDesc(BotMemorySummary::getUpdatedAt);
        Page<BotMemorySummary> result = summaryMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(),
                result.getCurrent(), result.getSize());
    }

    public BotMemorySummary updateSummary(Long id, MemorySummaryUpdateRequest request) {
        BotMemorySummary summary = summaryMapper.selectById(id);
        if (summary == null) {
            throw new NoSuchElementException("记忆总结不存在: " + id);
        }
        if (request == null || request.summary() == null) {
            throw new IllegalArgumentException("summary 不能为空");
        }
        String text = request.summary().trim();
        if (text.length() > 20000) {
            throw new IllegalArgumentException("总结过长");
        }
        summary.setSummary(text);
        summary.setUpdatedAt(LocalDateTime.now(ZONE));
        summaryMapper.updateById(summary);
        return summary;
    }

    public void deleteSummary(Long id) {
        if (summaryMapper.selectById(id) == null) {
            throw new NoSuchElementException("记忆总结不存在: " + id);
        }
        summaryMapper.deleteById(id);
    }

    private BotMemory get(Long id) {
        BotMemory memory = memoryMapper.selectById(id);
        if (memory == null) {
            throw new NoSuchElementException("记忆不存在: " + id);
        }
        return memory;
    }
}
