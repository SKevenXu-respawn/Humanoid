package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 长期记忆总结。 */
@Data
@TableName("bot_memory_summary")
public class BotMemorySummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String scopeType;

    private Long scopeId;

    private Long ownerUserId;

    private String summary;

    private Integer memoryCount;

    private LocalDateTime updatedAt;
}
