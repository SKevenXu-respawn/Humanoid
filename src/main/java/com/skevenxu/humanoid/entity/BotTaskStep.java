package com.skevenxu.humanoid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 任务步骤。 */
@Data
@TableName("bot_task_step")
public class BotTaskStep {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Integer stepNo;

    private String content;

    private String status;

    private Integer attempts;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}
