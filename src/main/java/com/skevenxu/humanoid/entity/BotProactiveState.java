package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 主动发言状态。 */
@Data
@TableName("bot_proactive_state")
public class BotProactiveState {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String messageType;

    /** 群号，私聊为 0 */
    private Long groupId;

    private LocalDateTime lastProactiveAt;

    private LocalDateTime lastCheckedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
