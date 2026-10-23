package com.skevenxu.humanoid.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotProactiveState;
import com.skevenxu.humanoid.entity.BotUser;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotProactiveStateMapper;
import com.skevenxu.humanoid.mapper.BotUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 定时检查空闲用户，由 AI 决定是否主动发起对话。 */
@Service
public class ProactiveService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final boolean enabled;
    private final int idleMinutes;
    private final int checkIntervalMinutes;
    private final Set<Long> userIds;
    private final String proactivePrompt;
    private final BotUserMapper userMapper;
    private final BotConversationMapper conversationMapper;
    private final BotProactiveStateMapper stateMapper;
    private final SummaryService summaryService;
    private final DeepSeekClient deepSeekClient;
    private final LlbotApiClient llbotApiClient;
    private final ContextStore contextStore;

    public ProactiveService(@Value("${humanoid.proactive.enabled:true}") boolean enabled,
                            @Value("${humanoid.proactive.idle-minutes:30}") int idleMinutes,
                            @Value("${humanoid.proactive.check-interval-minutes:10}") int checkIntervalMinutes,
                            @Value("${humanoid.proactive.user-ids:}") String userIds,
                            @Value("classpath:prompts/proactive-prompt.txt") Resource proactivePrompt,
                            BotUserMapper userMapper,
                            BotConversationMapper conversationMapper,
                            BotProactiveStateMapper stateMapper,
                            SummaryService summaryService,
                            DeepSeekClient deepSeekClient,
                            LlbotApiClient llbotApiClient,
                            ContextStore contextStore) {
        this.enabled = enabled;
        this.idleMinutes = idleMinutes;
        this.checkIntervalMinutes = checkIntervalMinutes;
        this.userIds = userIds.isBlank() ? Set.of() : Arrays.stream(userIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
        this.proactivePrompt = loadPrompt(proactivePrompt);
        this.userMapper = userMapper;
        this.conversationMapper = conversationMapper;
        this.stateMapper = stateMapper;
        this.summaryService = summaryService;
        this.deepSeekClient = deepSeekClient;
        this.llbotApiClient = llbotApiClient;
        this.contextStore = contextStore;
    }

    @Scheduled(cron = "${humanoid.proactive.cron:0 * * * * *}")
    public void scan() {
        if (!enabled) {
            return;
        }
        List<BotUser> users = userMapper.selectList(new LambdaQueryWrapper<BotUser>()
                .eq(BotUser::getEnabled, true));
        log.info("主动发言扫描触发, 白名单用户数={}", users.size());
        for (BotUser user : users) {
            if (!userIds.isEmpty() && !userIds.contains(user.getUserId())) {
                continue;
            }
            try {
                check(user);
            } catch (Exception e) {
                log.error("主动发言检查失败 user_id={}", user.getUserId(), e);
            }
        }
    }

    private void check(BotUser user) {
        Long userId = user.getUserId();
        BotConversation lastUserMessage = conversationMapper.selectOne(new LambdaQueryWrapper<BotConversation>()
                .eq(BotConversation::getUserId, userId)
                .eq(BotConversation::getMessageType, "private")
                .eq(BotConversation::getRole, "user")
                .isNull(BotConversation::getGroupId)
                .orderByDesc(BotConversation::getId)
                .last("LIMIT 1"));
        if (lastUserMessage == null) {
            log.info("主动发言判断 user_id={} 跳过: 无对话记录", userId);
            return;
        }
        LocalDateTime lastUserAt = lastUserMessage.getCreatedAt() != null
                ? lastUserMessage.getCreatedAt()
                : lastUserMessage.getEventTime();
        if (lastUserAt == null) {
            log.info("主动发言判断 user_id={} 跳过: 无法确定最后发言时间", userId);
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZONE);
        long idle = Duration.between(lastUserAt, now).toMinutes();
        if (idle < idleMinutes) {
            log.info("主动发言判断 user_id={} 跳过: 空闲不足 idle={}min < {}min", userId, idle, idleMinutes);
            return;
        }

        BotProactiveState state = findOrCreate(userId);
        // 上次主动发言后用户还没回复，不再打扰
        if (state.getLastProactiveAt() != null && !state.getLastProactiveAt().isBefore(lastUserAt)) {
            log.info("主动发言判断 user_id={} 跳过: 上次主动发言后用户未回复 idle={}min", userId, idle);
            return;
        }
        if (state.getLastCheckedAt() != null
                && Duration.between(state.getLastCheckedAt(), now).toMinutes() < checkIntervalMinutes) {
            log.info("主动发言判断 user_id={} 跳过: 检查冷却中 last_checked={}min 前",
                    userId, Duration.between(state.getLastCheckedAt(), now).toMinutes());
            return;
        }
        state.setLastCheckedAt(now);
        stateMapper.updateById(state);

        String summary = summaryService.peekSummary(userId, "private", null);
        List<BotConversation> recent = loadRecent(userId);
        String context = buildContext(now, lastUserAt, idle, summary, recent);
        log.info("主动发言判断 user_id={} 调用 AI: idle={}min summary_chars={} recent={}",
                userId, idle, summary.length(), recent.size());

        Optional<String> decision = deepSeekClient.chat(proactivePrompt,
                List.of(DeepSeekClient.ChatMessage.user(context, List.of())));
        if (decision.isEmpty()) {
            log.warn("主动发言判断 user_id={} AI 无返回", userId);
            return;
        }
        String reply = decision.get().trim();
        if (reply.isEmpty() || reply.toUpperCase(Locale.ROOT).startsWith("SKIP")) {
            log.info("主动发言判断 user_id={} 结果: SKIP (idle={}min)", userId, idle);
            return;
        }

        Optional<Long> messageId = llbotApiClient.sendPrivate(userId, reply);
        if (messageId.isEmpty()) {
            return;
        }
        saveBotMessage(userId, reply, messageId.get());
        state.setLastProactiveAt(LocalDateTime.now(ZONE));
        stateMapper.updateById(state);
        log.info("主动发言 user_id={} idle={}min message_id={}", userId, idle, messageId.get());
    }

    private List<BotConversation> loadRecent(Long userId) {
        List<BotConversation> rows = new ArrayList<>(conversationMapper.selectList(
                new LambdaQueryWrapper<BotConversation>()
                        .eq(BotConversation::getUserId, userId)
                        .eq(BotConversation::getMessageType, "private")
                        .isNull(BotConversation::getGroupId)
                        .orderByDesc(BotConversation::getId)
                        .last("LIMIT 10")));
        Collections.reverse(rows);
        return rows;
    }

    private String buildContext(LocalDateTime now, LocalDateTime lastUserAt, long idle,
                                String summary, List<BotConversation> recent) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前时间：").append(now.format(FORMAT))
                .append("（").append(now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA))
                .append("）\n");
        sb.append("用户最后一次发言：").append(lastUserAt.format(FORMAT))
                .append("（已过去 ").append(idle).append(" 分钟）\n");
        sb.append("已有的对话总结：\n")
                .append(summary == null || summary.isBlank() ? "无" : PromptSanitizer.clean(summary))
                .append("\n\n最近对话：\n");
        for (BotConversation row : recent) {
            String text = row.getContent() == null || row.getContent().isBlank() ? "[图片]" : row.getContent();
            sb.append("bot".equals(row.getRole()) ? "助手: " : "用户: ")
                    .append(PromptSanitizer.clean(text)).append("\n");
        }
        return sb.toString();
    }

    private BotProactiveState findOrCreate(Long userId) {
        BotProactiveState state = stateMapper.selectOne(new LambdaQueryWrapper<BotProactiveState>()
                .eq(BotProactiveState::getUserId, userId)
                .eq(BotProactiveState::getMessageType, "private")
                .eq(BotProactiveState::getGroupId, 0L));
        if (state != null) {
            return state;
        }
        state = new BotProactiveState();
        state.setUserId(userId);
        state.setMessageType("private");
        state.setGroupId(0L);
        state.setCreatedAt(LocalDateTime.now(ZONE));
        stateMapper.insert(state);
        return state;
    }

    private void saveBotMessage(Long userId, String reply, Long messageId) {
        BotConversation bot = new BotConversation();
        bot.setUserId(userId);
        bot.setRole("bot");
        bot.setMessageType("private");
        bot.setOnebotMessageId(messageId);
        bot.setContent(reply);
        bot.setEventTime(LocalDateTime.now(ZONE));
        bot.setCreatedAt(LocalDateTime.now(ZONE));
        conversationMapper.insert(bot);
        contextStore.appendAssistant(userId, "private", null, reply, java.util.List.of());
    }

    private static String loadPrompt(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 prompts/proactive-prompt.txt", e);
        }
    }
}
