package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 定时提醒。 */
@Data
@TableName("bot_reminder")
public class BotReminder {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String messageType;

    /** 群号，私聊为 0 */
    private Long groupId;

    /** 预设提示词：到点后交给主 AI 执行，不是直接发送的文本 */
    private String content;

    private LocalDateTime remindAt;

    private String status;

    private Integer attempts;

    private LocalDateTime createdAt;

    private LocalDateTime sentAt;
}
