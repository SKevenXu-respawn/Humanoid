package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 语音发送配置（单行）。 */
@Data
@TableName("bot_voice_config")
public class BotVoiceConfig {

    public static final long SINGLETON_ID = 1L;

    @TableId(type = IdType.INPUT)
    private Long id;

    private Boolean enabled;

    private String speaker;

    private LocalDateTime updatedAt;
}
