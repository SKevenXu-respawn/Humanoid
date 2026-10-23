package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 允许交互的群白名单。 */
@Data
@TableName("bot_group")
public class BotGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** QQ 群号 */
    private Long groupId;

    private String name;

    private String remark;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
