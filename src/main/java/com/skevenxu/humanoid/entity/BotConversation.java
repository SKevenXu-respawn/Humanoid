package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户与机器人的一条对话消息。 */
@Data
@TableName("bot_conversation")
public class BotConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对话用户 QQ 号 */
    private Long userId;

    /** user=用户发送 bot=机器人回复 */
    private String role;

    /** private=私聊 group=群聊 */
    private String messageType;

    /** 群号，仅群聊消息有 */
    private Long groupId;

    private Long onebotMessageId;

    private Long messageSeq;

    private String nickname;

    /** 纯文本内容 */
    private String content;

    /** OneBot message 消息段原始 JSON */
    private String segments;

    /** 事件 time 字段换算后的时间 */
    private LocalDateTime eventTime;

    private LocalDateTime createdAt;
}
