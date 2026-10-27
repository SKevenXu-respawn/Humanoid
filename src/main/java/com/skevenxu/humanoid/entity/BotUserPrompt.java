package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 按 QQ 绑定的自定义系统提示词。 */
@Data
@TableName("bot_user_prompt")
public class BotUserPrompt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String content;

    private LocalDateTime updatedAt;
}
