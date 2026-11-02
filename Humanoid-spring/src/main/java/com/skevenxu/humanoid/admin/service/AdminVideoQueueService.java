package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.ai.video.VideoQueueService;
import com.skevenxu.humanoid.entity.BotVideoQueue;
import com.skevenxu.humanoid.mapper.BotVideoQueueMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 视频分享队列管理。 */
@Service
public class AdminVideoQueueService {

    private final BotVideoQueueMapper queueMapper;
    private final VideoQueueService videoQueueService;

    public AdminVideoQueueService(BotVideoQueueMapper queueMapper,
                                  VideoQueueService videoQueueService) {
        this.queueMapper = queueMapper;
        this.videoQueueService = videoQueueService;
    }

    public PageResponse<BotVideoQueue> page(Integer page, Integer size, String status, Long userId) {
        LambdaQueryWrapper<BotVideoQueue> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            query.eq(BotVideoQueue::getStatus, status.trim().toUpperCase());
        }
        if (userId != null) {
            query.eq(BotVideoQueue::getUserId, userId);
        }
        // 按队列顺序展示，最前面的最先发送
        query.orderByAsc(BotVideoQueue::getId);
        Page<BotVideoQueue> result = queueMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(),
                result.getCurrent(), result.getSize());
    }

    public BotVideoQueue sendNow(Long id) {
        return videoQueueService.sendNow(id);
    }

    public BotVideoQueue skip(Long id) {
        return videoQueueService.skip(id);
    }

    public BotVideoQueue requeue(Long id) {
        return videoQueueService.requeue(id);
    }

    public void delete(Long id) {
        videoQueueService.delete(id);
    }
}
