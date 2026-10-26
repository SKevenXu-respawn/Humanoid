package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 视频分享队列。 */
@Data
@TableName("bot_video_queue")
public class BotVideoQueue {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String SOURCE_PROACTIVE = "PROACTIVE";
    public static final String SOURCE_USER = "USER";
    public static final String SOURCE_ADMIN = "ADMIN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String messageType;

    private Long groupId;

    private String url;

    private String title;

    private String comment;

    private String source;

    private String status;

    private Integer attempts;

    private LocalDateTime createdAt;

    private LocalDateTime sentAt;

    private Long sentMessageId;
}
