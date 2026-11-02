package com.skevenxu.humanoid.ai.voice;

import com.skevenxu.humanoid.entity.BotVoiceConfig;
import com.skevenxu.humanoid.mapper.BotVoiceConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 语音发送配置：数据库单行覆盖 properties 默认值，管理端修改后即时生效。 */
@Service
public class VoiceConfigService {

    private static final Logger log = LoggerFactory.getLogger(VoiceConfigService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotVoiceConfigMapper mapper;
    private final boolean defaultEnabled;
    private final String defaultSpeaker;

    public VoiceConfigService(BotVoiceConfigMapper mapper,
                              @Value("${humanoid.voice.enabled:true}") boolean defaultEnabled,
                              @Value("${humanoid.voice.speaker:zh_female_jiaochuannv_uranus_bigtts}") String defaultSpeaker) {
        this.mapper = mapper;
        this.defaultEnabled = defaultEnabled;
        this.defaultSpeaker = defaultSpeaker;
    }

    public boolean isEnabled() {
        return current().getEnabled();
    }

    public String speaker() {
        String value = current().getSpeaker();
        return value == null || value.isBlank() ? defaultSpeaker : value;
    }

    public String defaultSpeaker() {
        return defaultSpeaker;
    }

    public BotVoiceConfig current() {
        try {
            BotVoiceConfig row = mapper.selectById(BotVoiceConfig.SINGLETON_ID);
            if (row != null) {
                return row;
            }
            BotVoiceConfig created = defaults();
            try {
                mapper.insert(created);
            } catch (Exception e) {
                BotVoiceConfig existing = mapper.selectById(BotVoiceConfig.SINGLETON_ID);
                if (existing != null) {
                    return existing;
                }
            }
            return created;
        } catch (Exception e) {
            log.warn("读取语音配置失败，使用默认值: {}", e.toString());
            return defaults();
        }
    }

    public BotVoiceConfig update(Boolean enabled, String speaker) {
        if (enabled == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        String value = speaker == null ? "" : speaker.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("音色 ID 不能为空");
        }
        if (value.length() > 128) {
            throw new IllegalArgumentException("音色 ID 不能超过 128 个字符");
        }
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("音色 ID 只能包含字母、数字和下划线");
        }

        BotVoiceConfig row = current();
        row.setId(BotVoiceConfig.SINGLETON_ID);
        row.setEnabled(enabled);
        row.setSpeaker(value);
        row.setUpdatedAt(LocalDateTime.now(ZONE));
        if (mapper.selectById(BotVoiceConfig.SINGLETON_ID) == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        log.info("语音配置已更新 enabled={} speaker={}", enabled, value);
        return row;
    }

    private BotVoiceConfig defaults() {
        BotVoiceConfig row = new BotVoiceConfig();
        row.setId(BotVoiceConfig.SINGLETON_ID);
        row.setEnabled(defaultEnabled);
        row.setSpeaker(defaultSpeaker);
        row.setUpdatedAt(LocalDateTime.now(ZONE));
        return row;
    }
}
