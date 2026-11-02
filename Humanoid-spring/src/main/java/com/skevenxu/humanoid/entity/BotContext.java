package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 主 AI 上下文消息。 */
@Data
@TableName("bot_context")
public class BotContext {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String messageType;

    /** 群号，私聊为 0 */
    private Long groupId;

    private String role;

    private String content;

    /** JSON 数组：http URL / 沙箱路径 / image:<bot_image.id> */
    private String imageRefs;

    private LocalDateTime createdAt;
}
