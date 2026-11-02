package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 收到的好友申请（来自 OneBot request/friend 事件）。 */
@Data
@TableName("bot_friend_request")
public class BotFriendRequest {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String SOURCE_EVENT = "EVENT";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请人的 QQ 号 */
    private Long userId;

    /** OneBot 请求 flag，用于 set_friend_add_request */
    private String flag;

    private String comment;

    private String via;

    private String source;

    private String status;

    /** 同意时设置的好友备注 */
    private String remark;

    private LocalDateTime eventTime;

    private LocalDateTime createdAt;

    private LocalDateTime handledAt;
}
