package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 数据库中的密钥配置。 */
@Data
@TableName("bot_secret")
public class BotSecret {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String secretKey;

    private String secretValue;

    private LocalDateTime updatedAt;
}
