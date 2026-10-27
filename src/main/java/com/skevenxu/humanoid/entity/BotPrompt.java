package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 提示词配置。 */
@Data
@TableName("bot_prompt")
public class BotPrompt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String promptKey;

    private String content;

    private LocalDateTime updatedAt;
}
