package com.skevenxu.humanoid.ai.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.entity.BotPrompt;
import com.skevenxu.humanoid.mapper.BotPromptMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 提示词配置：数据库优先，首次启动从 classpath 默认文件迁移。 */
@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static final String SYSTEM = "system_prompt";
    public static final String SUMMARY = "summary_prompt";
    public static final String PROACTIVE = "proactive_prompt";
    public static final String TASK_EXECUTOR = "task_executor_prompt";
    public static final String MEMORY_SUMMARY = "memory_summary_prompt";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(SYSTEM, "系统提示词");
        LABELS.put(SUMMARY, "滚动总结提示词");
        LABELS.put(PROACTIVE, "主动行为提示词");
        LABELS.put(TASK_EXECUTOR, "任务执行器提示词");
        LABELS.put(MEMORY_SUMMARY, "记忆总结提示词");
    }

    private final BotPromptMapper promptMapper;
    private final Map<String, String> defaults = new LinkedHashMap<>();

    public PromptService(BotPromptMapper promptMapper,
                         @Value("classpath:prompts/system-prompt.txt") Resource system,
                         @Value("classpath:prompts/summary-prompt.txt") Resource summary,
                         @Value("classpath:prompts/proactive-prompt.txt") Resource proactive,
                         @Value("classpath:prompts/task-executor-prompt.txt") Resource taskExecutor,
                         @Value("classpath:prompts/memory-summary-prompt.txt") Resource memorySummary) {
        this.promptMapper = promptMapper;
        defaults.put(SYSTEM, load(system));
        defaults.put(SUMMARY, load(summary));
        defaults.put(PROACTIVE, load(proactive));
        defaults.put(TASK_EXECUTOR, load(taskExecutor));
        defaults.put(MEMORY_SUMMARY, load(memorySummary));
    }

    @PostConstruct
    void migrateDefaultsToDatabase() {
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            try {
                if (findRow(entry.getKey()) != null || !StringUtils.hasText(entry.getValue())) {
                    continue;
                }
                BotPrompt prompt = new BotPrompt();
                prompt.setPromptKey(entry.getKey());
                prompt.setContent(entry.getValue());
                prompt.setUpdatedAt(LocalDateTime.now(ZONE));
                promptMapper.insert(prompt);
                log.info("已将默认提示词迁移到数据库 key={}", entry.getKey());
            } catch (Exception e) {
                log.warn("迁移提示词失败 key={}: {}", entry.getKey(), e.toString());
            }
        }
    }

    public String systemPrompt() {
        return get(SYSTEM);
    }

    public String summaryPrompt() {
        return get(SUMMARY);
    }

    public String proactivePrompt() {
        return get(PROACTIVE);
    }

    public String taskExecutorPrompt() {
        return get(TASK_EXECUTOR);
    }

    public String memorySummaryPrompt() {
        return get(MEMORY_SUMMARY);
    }

    public String get(String key) {
        if (!LABELS.containsKey(key)) {
            throw new IllegalArgumentException("未知提示词: " + key);
        }
        BotPrompt row = findRow(key);
        if (row != null && StringUtils.hasText(row.getContent())) {
            return row.getContent();
        }
        return defaults.getOrDefault(key, "");
    }

    public List<PromptInfo> list() {
        List<PromptInfo> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : LABELS.entrySet()) {
            BotPrompt row = findRow(entry.getKey());
            boolean fromDb = row != null && StringUtils.hasText(row.getContent());
            list.add(new PromptInfo(entry.getKey(), entry.getValue(),
                    fromDb ? row.getContent() : defaults.getOrDefault(entry.getKey(), ""),
                    fromDb ? "DB" : "DEFAULT",
                    row == null ? null : row.getUpdatedAt()));
        }
        return list;
    }

    public PromptInfo save(String key, String content) {
        if (!LABELS.containsKey(key)) {
            throw new IllegalArgumentException("未知提示词: " + key);
        }
        String text = content == null ? "" : content.trim();
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("提示词不能为空；如需恢复默认请使用重置");
        }
        if (text.length() > 200_000) {
            throw new IllegalArgumentException("提示词过长");
        }
        BotPrompt row = findRow(key);
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (row == null) {
            row = new BotPrompt();
            row.setPromptKey(key);
            row.setContent(text);
            row.setUpdatedAt(now);
            promptMapper.insert(row);
        } else {
            row.setContent(text);
            row.setUpdatedAt(now);
            promptMapper.updateById(row);
        }
        log.info("提示词已更新 key={} chars={}", key, text.length());
        return info(key);
    }

    /** 删除数据库覆盖，恢复 classpath 默认值。 */
    public PromptInfo reset(String key) {
        if (!LABELS.containsKey(key)) {
            throw new IllegalArgumentException("未知提示词: " + key);
        }
        promptMapper.delete(new LambdaQueryWrapper<BotPrompt>().eq(BotPrompt::getPromptKey, key));
        log.info("提示词已恢复默认 key={}", key);
        return info(key);
    }

    public PromptInfo info(String key) {
        return list().stream().filter(item -> item.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知提示词: " + key));
    }

    private BotPrompt findRow(String key) {
        try {
            return promptMapper.selectOne(new LambdaQueryWrapper<BotPrompt>()
                    .eq(BotPrompt::getPromptKey, key));
        } catch (Exception e) {
            log.warn("读取提示词失败 key={}: {}", key, e.toString());
            return null;
        }
    }

    private static String load(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取提示词资源: " + resource.getDescription(), e);
        }
    }

    public record PromptInfo(String key, String label, String content, String source,
                             LocalDateTime updatedAt) {
    }
}
