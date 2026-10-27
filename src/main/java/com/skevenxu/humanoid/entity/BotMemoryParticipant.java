package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 长期记忆参与人。 */
@Data
@TableName("bot_memory_participant")
public class BotMemoryParticipant {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memoryId;

    private Long userId;
}
