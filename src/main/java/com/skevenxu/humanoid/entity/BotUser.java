package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 允许与机器人交互的用户白名单。 */
@Data
@TableName("bot_user")
public class BotUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** QQ 号 */
    private Long userId;

    private String nickname;

    private String remark;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
