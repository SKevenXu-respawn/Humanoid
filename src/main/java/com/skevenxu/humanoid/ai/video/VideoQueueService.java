package com.skevenxu.humanoid.ai.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.skevenxu.humanoid.ai.ContextStore;
import com.skevenxu.humanoid.ai.PromptSanitizer;
import com.skevenxu.humanoid.ai.tools.ToolContext;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotVideoQueue;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotVideoQueueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 视频分享队列：先进先出，每轮每个用户最多发一条，优先处理最早入队的需求。
 */
@Service
public class VideoQueueService {

    private static final Logger log = LoggerFactory.getLogger(VideoQueueService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_URL = 1000;
    private static final int MAX_TITLE = 500;
    private static final int MAX_COMMENT = 500;

    private final BotVideoQueueMapper queueMapper;
    private final LlbotApiClient llbotApiClient;
    private final BotConversationMapper conversationMapper;
    private final ContextStore contextStore;
    private final boolean enabled;
    private final int maxPerTick;
    private final boolean strictUserMode;

    public VideoQueueService(BotVideoQueueMapper queueMapper,
                             LlbotApiClient llbotApiClient,
                             BotConversationMapper conversationMapper,
                             ContextStore contextStore,
                             @Value("${humanoid.video-queue.enabled:true}") boolean enabled,
                             @Value("${humanoid.video-queue.max-per-tick:5}") int maxPerTick,
                             @Value("${humanoid.video-queue.mode:strict}") String mode) {
        this.queueMapper = queueMapper;
        this.llbotApiClient = llbotApiClient;
        this.conversationMapper = conversationMapper;
        this.contextStore = contextStore;
        this.enabled = enabled;
        this.maxPerTick = Math.max(1, Math.min(20, maxPerTick));
        this.strictUserMode = !"fair".equalsIgnoreCase(mode == null ? "" : mode.trim());
        log.info("视频队列: enabled={} mode={} max-per-tick={}",
                enabled, this.strictUserMode ? "strict" : "fair", this.maxPerTick);
    }

    /** 入队；同一用户已存在相同链接的待发送记录时返回 false。 */
    public boolean enqueue(Long userId, String messageType, Long groupId,
                           String url, String title, String comment, String source) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("视频链接不能为空");
        }
        String link = url.trim();
        if (!link.startsWith("http://") && !link.startsWith("https://")) {
            throw new IllegalArgumentException("视频链接必须是 http/https");
        }
        if (link.length() > MAX_URL) {
            throw new IllegalArgumentException("视频链接过长");
        }
        String type = "group".equals(messageType) ? "group" : "private";
        long group = ContextStore.normalizeGroup(type, groupId);

        Long existing = queueMapper.selectCount(new LambdaQueryWrapper<BotVideoQueue>()
                .eq(BotVideoQueue::getUserId, userId)
                .eq(BotVideoQueue::getMessageType, type)
                .eq(BotVideoQueue::getGroupId, group)
                .eq(BotVideoQueue::getStatus, BotVideoQueue.STATUS_PENDING)
                .eq(BotVideoQueue::getUrl, link));
        if (existing != null && existing > 0) {
            return false;
        }

        BotVideoQueue item = new BotVideoQueue();
        item.setUserId(userId);
        item.setMessageType(type);
        item.setGroupId(group);
        item.setUrl(link);
        item.setTitle(truncate(title, MAX_TITLE));
        item.setComment(truncate(comment, MAX_COMMENT));
        item.setSource(StringUtils.hasText(source) ? source : BotVideoQueue.SOURCE_PROACTIVE);
        item.setStatus(BotVideoQueue.STATUS_PENDING);
        item.setAttempts(0);
        item.setCreatedAt(LocalDateTime.now(ZONE));
        queueMapper.insert(item);
        log.info("视频入队 id={} user_id={} title={}", item.getId(), userId, item.getTitle());
        return true;
    }

    public long countPending(ToolContext.ChatScope scope) {
        Long count = queueMapper.selectCount(new LambdaQueryWrapper<BotVideoQueue>()
                .eq(BotVideoQueue::getUserId, scope.userId())
                .eq(BotVideoQueue::getMessageType, scope.messageType())
                .eq(BotVideoQueue::getGroupId,
                        ContextStore.normalizeGroup(scope.messageType(), scope.groupId()))
                .eq(BotVideoQueue::getStatus, BotVideoQueue.STATUS_PENDING));
        return count == null ? 0 : count;
    }

    public long countPendingAll() {
        Long count = queueMapper.selectCount(new LambdaQueryWrapper<BotVideoQueue>()
                .eq(BotVideoQueue::getStatus, BotVideoQueue.STATUS_PENDING));
        return count == null ? 0 : count;
    }

    public Optional<BotVideoQueue> peek(ToolContext.ChatScope scope) {
        return Optional.ofNullable(queueMapper.selectOne(new LambdaQueryWrapper<BotVideoQueue>()
                .eq(BotVideoQueue::getUserId, scope.userId())
                .eq(BotVideoQueue::getMessageType, scope.messageType())
                .eq(BotVideoQueue::getGroupId,
                        ContextStore.normalizeGroup(scope.messageType(), scope.groupId()))
                .eq(BotVideoQueue::getStatus, BotVideoQueue.STATUS_PENDING)
                .orderByAsc(BotVideoQueue::getId)
                .last("LIMIT 1")));
    }

    /** 用户主动要下一个：立即发送当前会话最早的待发送视频。 */
    public String sendNext(ToolContext.ChatScope scope) {
        Optional<BotVideoQueue> next = peek(scope);
        if (next.isEmpty()) {
            return "队列里没有待发送的视频";
        }
        BotVideoQueue item = next.get();
        if (!claim(item)) {
            return "队列正在处理，请稍后再试";
        }
        if (!deliver(item)) {
            markFailed(item);
            return "视频发送失败，请稍后再试";
        }
        return "已发送视频" + (StringUtils.hasText(item.getTitle()) ? "《" + item.getTitle() + "》" : "");
    }

    /** 后台立即发送（任何状态都可以重新发送）。 */
    public BotVideoQueue sendNow(Long id) {
        BotVideoQueue item = get(id);
        queueMapper.update(null, new UpdateWrapper<BotVideoQueue>()
                .eq("id", id)
                .set("status", BotVideoQueue.STATUS_PENDING)
                .set("attempts", 0));
        item.setStatus(BotVideoQueue.STATUS_PENDING);
        item.setAttempts(0);
        if (claim(item)) {
            if (!deliver(item)) {
                markFailed(item);
            }
        }
        return get(id);
    }

    public BotVideoQueue skip(Long id) {
        BotVideoQueue item = get(id);
        if (BotVideoQueue.STATUS_SENT.equals(item.getStatus())) {
            throw new IllegalStateException("已发送的视频不能跳过");
        }
        item.setStatus(BotVideoQueue.STATUS_SKIPPED);
        queueMapper.updateById(item);
        return item;
    }

    public BotVideoQueue requeue(Long id) {
        BotVideoQueue item = get(id);
        queueMapper.update(null, new UpdateWrapper<BotVideoQueue>()
                .eq("id", id)
                .set("status", BotVideoQueue.STATUS_PENDING)
                .set("attempts", 0)
                .set("sent_at", null)
                .set("sent_message_id", null));
        return get(id);
    }

    public void delete(Long id) {
        get(id);
        queueMapper.deleteById(id);
    }

    /**
     * 严格模式：先找到队列里最早的一条，把它所属用户的全部待发送视频按顺序发完，
     * 再轮到下一个用户；公平模式：每个用户每轮最多一条。
     */
    @Scheduled(cron = "${humanoid.video-queue.cron:0 */5 * * * *}")
    public void dispatch() {
        if (!enabled) {
            return;
        }
        long pending = countPendingAll();
        List<BotVideoQueue> batch = strictUserMode ? selectStrictBatch() : queueMapper.selectNextPerUser(maxPerTick);
        log.info("视频队列扫描触发, 模式={} 待发送={} 本轮候选={}",
                strictUserMode ? "严格单用户" : "公平轮询", pending, batch.size());

        int sent = 0;
        for (BotVideoQueue item : batch) {
            if (!claim(item)) {
                continue;
            }
            if (deliver(item)) {
                sent++;
            } else {
                markFailed(item);
            }
        }
        log.info("视频队列本轮发送={} 剩余待发送={}", sent, countPendingAll());
    }

    private List<BotVideoQueue> selectStrictBatch() {
        BotVideoQueue first = queueMapper.selectOne(new LambdaQueryWrapper<BotVideoQueue>()
                .eq(BotVideoQueue::getStatus, BotVideoQueue.STATUS_PENDING)
                .orderByAsc(BotVideoQueue::getId)
                .last("LIMIT 1"));
        if (first == null) {
            return List.of();
        }
        return queueMapper.selectList(new LambdaQueryWrapper<BotVideoQueue>()
                .eq(BotVideoQueue::getUserId, first.getUserId())
                .eq(BotVideoQueue::getStatus, BotVideoQueue.STATUS_PENDING)
                .orderByAsc(BotVideoQueue::getId)
                .last("LIMIT " + maxPerTick));
    }

    private boolean claim(BotVideoQueue item) {
        int attempts = item.getAttempts() == null ? 0 : item.getAttempts();
        int updated = queueMapper.update(null, new UpdateWrapper<BotVideoQueue>()
                .eq("id", item.getId())
                .eq("status", BotVideoQueue.STATUS_PENDING)
                .eq("attempts", attempts)
                .setSql("attempts = attempts + 1"));
        if (updated == 0) {
            return false;
        }
        item.setAttempts(attempts + 1);
        return true;
    }

    private boolean deliver(BotVideoQueue item) {
        String text = buildText(item);
        Optional<Long> messageId = "group".equals(item.getMessageType())
                ? llbotApiClient.sendGroup(item.getGroupId(), text)
                : llbotApiClient.sendPrivate(item.getUserId(), text);
        if (messageId.isEmpty()) {
            log.warn("视频队列发送失败 id={} user_id={}", item.getId(), item.getUserId());
            return false;
        }

        item.setStatus(BotVideoQueue.STATUS_SENT);
        item.setSentAt(LocalDateTime.now(ZONE));
        item.setSentMessageId(messageId.get());
        queueMapper.updateById(item);
        saveBotMessage(item, text, messageId.get());
        log.info("视频队列已发送 id={} user_id={} message_id={}",
                item.getId(), item.getUserId(), messageId.get());
        return true;
    }

    private void markFailed(BotVideoQueue item) {
        item.setStatus(BotVideoQueue.STATUS_FAILED);
        queueMapper.updateById(item);
    }

    private void saveBotMessage(BotVideoQueue item, String content, Long messageId) {
        BotConversation bot = new BotConversation();
        bot.setUserId(item.getUserId());
        bot.setRole("bot");
        bot.setMessageType(item.getMessageType());
        if ("group".equals(item.getMessageType())) {
            bot.setGroupId(item.getGroupId());
        }
        bot.setOnebotMessageId(messageId);
        bot.setContent(content);
        bot.setEventTime(LocalDateTime.now(ZONE));
        bot.setCreatedAt(LocalDateTime.now(ZONE));
        conversationMapper.insert(bot);
        contextStore.appendAssistant(item.getUserId(), item.getMessageType(),
                item.getGroupId(), content, List.of());
    }

    private static String buildText(BotVideoQueue item) {
        StringBuilder sb = new StringBuilder("看到一个有意思的视频，分享给你～\n");
        if (StringUtils.hasText(item.getTitle())) {
            sb.append(item.getTitle().trim()).append("\n");
        }
        sb.append(item.getUrl());
        if (StringUtils.hasText(item.getComment())) {
            sb.append("\n").append(item.getComment().trim());
        }
        return PromptSanitizer.truncate(sb.toString(), 1500);
    }

    private BotVideoQueue get(Long id) {
        BotVideoQueue item = queueMapper.selectById(id);
        if (item == null) {
            throw new NoSuchElementException("队列记录不存在: " + id);
        }
        return item;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
