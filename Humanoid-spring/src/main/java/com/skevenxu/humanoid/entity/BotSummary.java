package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 每个会话的提示词附加词：滚动总结。 */
@Data
@TableName("bot_summary")
public class BotSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** QQ 号 */
    private Long userId;

    /** private=私聊 group=群聊 */
    private String messageType;

    /** 群号，私聊为 0 */
    private Long groupId;

    private String summary;

    /** 已总结到的最新对话 id */
    private Long lastConversationId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
