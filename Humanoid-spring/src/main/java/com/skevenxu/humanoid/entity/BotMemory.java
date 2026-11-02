package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 长期记忆。 */
@Data
@TableName("bot_memory")
public class BotMemory {

    public static final String TYPE_PRIVATE = "PRIVATE";
    public static final String TYPE_GROUP = "GROUP";
    public static final String TYPE_RELATIONSHIP = "RELATIONSHIP";

    public static final String SCOPE_USER = "USER";
    public static final String SCOPE_GROUP = "GROUP";
    public static final String SCOPE_GLOBAL = "GLOBAL";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUMMARIZED = "SUMMARIZED";
    public static final String STATUS_DELETED = "DELETED";

    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_USER = "USER";
    public static final String SOURCE_ADMIN = "ADMIN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String memoryType;

    private String scopeType;

    private Long scopeId;

    private Long ownerUserId;

    private String content;

    private String contentHash;

    private Integer importance;

    private String source;

    private Long sourceConversationId;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime lastUsedAt;
}
