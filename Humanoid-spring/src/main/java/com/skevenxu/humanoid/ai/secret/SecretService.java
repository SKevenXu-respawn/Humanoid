package com.skevenxu.humanoid.ai.secret;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.entity.BotSecret;
import com.skevenxu.humanoid.mapper.BotSecretMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 密钥配置：数据库优先级高于环境变量/配置文件，管理端只展示掩码。 */
@Service
public class SecretService {

    private static final Logger log = LoggerFactory.getLogger(SecretService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static final String DEEPSEEK_API_KEY = "deepseek_api_key";
    public static final String VOICE_API_KEY = "voice_api_key";
    public static final String LLBOT_API_TOKEN = "llbot_api_token";
    public static final String BRAVE_API_KEY = "brave_api_key";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final java.util.Set<String> REQUIRED = java.util.Set.of(
            DEEPSEEK_API_KEY, VOICE_API_KEY);

    static {
        LABELS.put(DEEPSEEK_API_KEY, "DeepSeek API Key");
        LABELS.put(VOICE_API_KEY, "豆包语音 Key（TTS + ASR）");
        LABELS.put(LLBOT_API_TOKEN, "LLBot API Token");
        LABELS.put(BRAVE_API_KEY, "Brave Search API Key");
    }

    private final BotSecretMapper secretMapper;
    private final String deepseekDefault;
    private final String voiceDefault;
    private final String asrDefault;
    private final String llbotTokenDefault;
    private final String braveDefault;

    public SecretService(BotSecretMapper secretMapper,
                         @Value("${spring.ai.deepseek.api-key:}") String deepseekDefault,
                         @Value("${humanoid.voice.api-key:}") String voiceDefault,
                         @Value("${humanoid.asr.api-key:}") String asrDefault,
                         @Value("${llbot.api-token:}") String llbotTokenDefault,
                         @Value("${humanoid.tools.brave-api-key:}") String braveDefault) {
        this.secretMapper = secretMapper;
        this.deepseekDefault = trim(deepseekDefault);
        this.voiceDefault = trim(voiceDefault);
        this.asrDefault = trim(asrDefault);
        this.llbotTokenDefault = trim(llbotTokenDefault);
        this.braveDefault = trim(braveDefault);
    }

    /** 首次启动时把环境变量里的密钥写入数据库，之后统一由管理端维护。 */
    @PostConstruct
    void migrateDefaultsToDatabase() {
        for (String key : LABELS.keySet()) {
            try {
                if (findRow(key) != null) {
                    continue;
                }
                String value = defaultFor(key);
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                BotSecret row = new BotSecret();
                row.setSecretKey(key);
                row.setSecretValue(value);
                row.setUpdatedAt(LocalDateTime.now(ZONE));
                secretMapper.insert(row);
                log.info("已将环境变量中的密钥迁移到数据库 key={}", key);
            } catch (Exception e) {
                log.warn("迁移密钥到数据库失败 key={}: {}", key, e.toString());
            }
        }
    }

    public String deepseekKey() {
        return effective(DEEPSEEK_API_KEY, deepseekDefault);
    }

    /** 语音合成与识别共用；ASR 单独配置时作为回退。 */
    public String voiceKey() {
        String value = effective(VOICE_API_KEY, voiceDefault);
        return StringUtils.hasText(value) ? value : asrDefault;
    }

    public String asrKey() {
        return voiceKey();
    }

    public String llbotToken() {
        return effective(LLBOT_API_TOKEN, llbotTokenDefault);
    }

    public String braveKey() {
        return effective(BRAVE_API_KEY, braveDefault);
    }

    public String get(String key) {
        if (!LABELS.containsKey(key)) {
            throw new IllegalArgumentException("未知密钥: " + key);
        }
        return effective(key, defaultFor(key));
    }

    public List<SecretInfo> list() {
        List<SecretInfo> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : LABELS.entrySet()) {
            BotSecret row = findRow(entry.getKey());
            String dbValue = row == null ? "" : trim(row.getSecretValue());
            String envValue = trim(defaultFor(entry.getKey()));
            boolean fromDb = StringUtils.hasText(dbValue);
            String effective = fromDb ? dbValue : envValue;
            list.add(new SecretInfo(entry.getKey(), entry.getValue(),
                    StringUtils.hasText(effective), mask(effective),
                    fromDb ? "DB" : (StringUtils.hasText(envValue) ? "ENV" : "NONE"),
                    row == null ? null : row.getUpdatedAt(),
                    REQUIRED.contains(entry.getKey())));
        }
        return list;
    }

    public SecretInfo save(String key, String value) {
        if (!LABELS.containsKey(key)) {
            throw new IllegalArgumentException("未知密钥: " + key);
        }
        String text = trim(value);
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("密钥不能为空；如需恢复环境变量请使用清除");
        }
        if (text.length() > 2000) {
            throw new IllegalArgumentException("密钥过长");
        }

        BotSecret row = findRow(key);
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (row == null) {
            row = new BotSecret();
            row.setSecretKey(key);
            row.setSecretValue(text);
            row.setUpdatedAt(now);
            secretMapper.insert(row);
        } else {
            row.setSecretValue(text);
            row.setUpdatedAt(now);
            secretMapper.updateById(row);
        }
        log.info("密钥已更新 key={}", key);
        return info(key);
    }

    public SecretInfo clear(String key) {
        if (!LABELS.containsKey(key)) {
            throw new IllegalArgumentException("未知密钥: " + key);
        }
        // 保留一条空值记录，避免下次启动又把环境变量迁移回来
        BotSecret row = findRow(key);
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (row == null) {
            row = new BotSecret();
            row.setSecretKey(key);
            row.setSecretValue("");
            row.setUpdatedAt(now);
            secretMapper.insert(row);
        } else {
            row.setSecretValue("");
            row.setUpdatedAt(now);
            secretMapper.updateById(row);
        }
        log.info("密钥已清除，回退到环境变量 key={}", key);
        return info(key);
    }

    public SecretInfo info(String key) {
        return list().stream().filter(item -> item.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知密钥: " + key));
    }

    private String effective(String key, String fallback) {
        BotSecret row = findRow(key);
        if (row != null && StringUtils.hasText(row.getSecretValue())) {
            return row.getSecretValue().trim();
        }
        return fallback == null ? "" : fallback;
    }

    private BotSecret findRow(String key) {
        try {
            return secretMapper.selectOne(new LambdaQueryWrapper<BotSecret>()
                    .eq(BotSecret::getSecretKey, key));
        } catch (Exception e) {
            log.warn("读取密钥失败 key={}: {}", key, e.toString());
            return null;
        }
    }

    private String defaultFor(String key) {
        return switch (key) {
            case DEEPSEEK_API_KEY -> deepseekDefault;
            case VOICE_API_KEY -> voiceDefault;
            case LLBOT_API_TOKEN -> llbotTokenDefault;
            case BRAVE_API_KEY -> braveDefault;
            default -> "";
        };
    }

    private static String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.trim();
        if (text.length() <= 8) {
            return "****";
        }
        return "****" + text.substring(text.length() - 4);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record SecretInfo(String key, String label, boolean configured, String masked,
                             String source, LocalDateTime updatedAt, boolean required) {
    }
}
