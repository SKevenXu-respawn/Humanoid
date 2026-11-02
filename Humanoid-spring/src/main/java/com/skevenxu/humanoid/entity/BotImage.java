package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 已发送图片的 base64 副本。 */
@Data
@TableName("bot_image")
public class BotImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String messageType;

    /** 群号，私聊为 0 */
    private Long groupId;

    /** 关联的 bot_conversation.id */
    private Long conversationId;

    /** 原始地址或文件路径 */
    private String source;

    /** base64 data URL */
    private String dataUrl;

    private LocalDateTime createdAt;
}
