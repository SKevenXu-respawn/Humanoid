-- 接收白名单：只有 enabled = 1 的用户，其私聊/群聊消息才会被处理和记录
CREATE TABLE IF NOT EXISTS bot_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL COMMENT 'QQ 号',
    nickname   VARCHAR(64)  DEFAULT NULL COMMENT '最近一次收到的昵称',
    remark     VARCHAR(128) DEFAULT NULL COMMENT '备注名',
    enabled    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=接收该用户消息 0=忽略',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='允许与机器人交互的用户白名单';

-- 对话记录：用户消息（role=user）与机器人回复（role=bot）都写入此表
CREATE TABLE IF NOT EXISTS bot_conversation (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL COMMENT '对话用户 QQ 号',
    role              VARCHAR(16)  NOT NULL COMMENT 'user=用户发送 bot=机器人回复',
    message_type      VARCHAR(16)  NOT NULL COMMENT 'private=私聊 group=群聊',
    group_id          BIGINT       DEFAULT NULL COMMENT '群号，仅群聊消息有',
    onebot_message_id BIGINT       DEFAULT NULL COMMENT 'OneBot message_id，机器人回复可用于撤回/引用',
    message_seq       BIGINT       DEFAULT NULL COMMENT 'OneBot message_seq',
    nickname          VARCHAR(64)  DEFAULT NULL COMMENT '发送者昵称',
    content           TEXT         DEFAULT NULL COMMENT '纯文本内容（消息段 text 拼接）',
    segments          JSON         DEFAULT NULL COMMENT 'OneBot message 消息段原始 JSON',
    event_time        DATETIME     DEFAULT NULL COMMENT '事件 time 字段（秒级时间戳换算）',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, created_at),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户与机器人的对话记录';

-- 每个会话（用户 + 私聊/群聊）的提示词附加词：滚动总结 + 已总结到的对话 id
CREATE TABLE IF NOT EXISTS bot_summary (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL COMMENT 'QQ 号',
    message_type         VARCHAR(16)  NOT NULL COMMENT 'private=私聊 group=群聊',
    group_id             BIGINT       NOT NULL DEFAULT 0 COMMENT '群号，私聊为 0',
    summary              TEXT         DEFAULT NULL COMMENT '滚动总结（附加到系统提示词）',
    last_conversation_id BIGINT       NOT NULL DEFAULT 0 COMMENT '已总结到的最新对话 id',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scope (user_id, message_type, group_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='每个会话的提示词附加词（滚动总结）';

-- 主动发言状态：记录上次主动发言/上次检查时间，防止骚扰和重复调用 AI
CREATE TABLE IF NOT EXISTS bot_proactive_state (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    user_id           BIGINT      NOT NULL COMMENT 'QQ 号',
    message_type      VARCHAR(16) NOT NULL DEFAULT 'private' COMMENT 'private / group',
    group_id          BIGINT      NOT NULL DEFAULT 0 COMMENT '群号，私聊为 0',
    last_proactive_at DATETIME    DEFAULT NULL COMMENT '上次主动发言时间',
    last_checked_at   DATETIME    DEFAULT NULL COMMENT '上次调用 AI 判断的时间',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scope (user_id, message_type, group_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='AI 主动发言状态';

-- 定时提醒：AI 通过工具创建，到点后由调度器发送
CREATE TABLE IF NOT EXISTS bot_reminder (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL COMMENT '接收提醒的 QQ 号',
    message_type VARCHAR(16)  NOT NULL DEFAULT 'private' COMMENT 'private / group',
    group_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '群号，私聊为 0',
    content      VARCHAR(1000) NOT NULL COMMENT '到点后发送的内容',
    remind_at    DATETIME     NOT NULL COMMENT '提醒时间（北京时间）',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / FAILED',
    attempts     INT          NOT NULL DEFAULT 0 COMMENT '发送尝试次数',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at      DATETIME     DEFAULT NULL COMMENT '实际发送时间',
    PRIMARY KEY (id),
    KEY idx_status_time (status, remind_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='定时提醒';

-- AI 任务：一个任务包含多个步骤
CREATE TABLE IF NOT EXISTS bot_task (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL COMMENT 'QQ 号',
    message_type VARCHAR(16)  NOT NULL COMMENT 'private / group',
    group_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '群号，私聊为 0',
    title        VARCHAR(200) NOT NULL COMMENT '任务标题',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / IN_PROGRESS / PAUSED / COMPLETED',
    pause_reason VARCHAR(500) DEFAULT NULL COMMENT '暂停原因',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_scope_status (user_id, message_type, group_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='AI 任务';

CREATE TABLE IF NOT EXISTS bot_task_step (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    task_id      BIGINT       NOT NULL COMMENT '所属任务 id',
    step_no      INT          NOT NULL COMMENT '步骤序号，从 1 开始',
    content      VARCHAR(500) NOT NULL COMMENT '步骤内容',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / COMPLETED',
    attempts     INT          NOT NULL DEFAULT 0 COMMENT '执行尝试次数',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_task_no (task_id, step_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='AI 任务步骤';

-- 工具调用日志：记录每次工具调用的参数和结果摘要，供后续上下文使用
CREATE TABLE IF NOT EXISTS bot_tool_call (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL COMMENT 'QQ 号',
    message_type VARCHAR(16) NOT NULL COMMENT 'private / group',
    group_id     BIGINT      NOT NULL DEFAULT 0 COMMENT '群号，私聊为 0',
    tool_name    VARCHAR(64) NOT NULL COMMENT '工具名',
    arguments    TEXT        DEFAULT NULL COMMENT '参数摘要（截断）',
    result       TEXT        DEFAULT NULL COMMENT '结果摘要（截断）',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_scope_id (user_id, message_type, group_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='AI 工具调用日志';

-- 已发送图片的 base64 副本，供后续按需查看（独立于 bot_conversation，避免主查询扫大字段）
CREATE TABLE IF NOT EXISTS bot_image (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL COMMENT 'QQ 号',
    message_type    VARCHAR(16)  NOT NULL COMMENT 'private / group',
    group_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '群号，私聊为 0',
    conversation_id BIGINT       DEFAULT NULL COMMENT '关联的 bot_conversation.id',
    source          VARCHAR(1000) DEFAULT NULL COMMENT '原始地址或文件路径',
    data_url        LONGTEXT     NOT NULL COMMENT 'base64 data URL',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_scope_id (user_id, message_type, group_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='已发送图片的 base64 副本';

-- 主 AI 专用上下文：只存参与模型请求的消息，与审计表 bot_conversation 分离
CREATE TABLE IF NOT EXISTS bot_context (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL COMMENT 'QQ 号',
    message_type VARCHAR(16) NOT NULL COMMENT 'private / group',
    group_id     BIGINT      NOT NULL DEFAULT 0 COMMENT '群号，私聊为 0',
    role         VARCHAR(16) NOT NULL COMMENT 'user / assistant',
    content      TEXT        DEFAULT NULL COMMENT '消息文本',
    image_refs   JSON        DEFAULT NULL COMMENT '图片引用数组：http URL / 沙箱路径 / image:<bot_image.id>',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_scope_id (user_id, message_type, group_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='主 AI 上下文消息';

-- 好友申请：接收 OneBot request/friend 事件后落库，管理台据此同意/拒绝
CREATE TABLE IF NOT EXISTS bot_friend_request (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL COMMENT '申请人 QQ 号',
    flag       VARCHAR(512) NOT NULL COMMENT 'OneBot 请求 flag',
    comment    VARCHAR(500) DEFAULT NULL COMMENT '附加消息',
    via        VARCHAR(64)  DEFAULT NULL COMMENT '申请来源',
    source     VARCHAR(16)  NOT NULL DEFAULT 'EVENT' COMMENT 'EVENT=事件推送',
    status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
    remark     VARCHAR(64)  DEFAULT NULL COMMENT '同意时设置的好友备注',
    event_time DATETIME     DEFAULT NULL COMMENT '事件时间',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at DATETIME     DEFAULT NULL COMMENT '处理时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_flag (flag),
    KEY idx_status_id (status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='好友申请';

-- 语音发送配置（单行，id=1）：管理端可改音色与开关，即时生效
CREATE TABLE IF NOT EXISTS bot_voice_config (
    id         BIGINT       NOT NULL COMMENT '固定为 1',
    enabled    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用 sendVoice 0=关闭',
    speaker    VARCHAR(128) NOT NULL COMMENT '豆包 TTS 音色 ID',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='语音发送配置';

-- 群白名单：只有登记且 enabled=1 的群才会接收和回复消息
CREATE TABLE IF NOT EXISTS bot_group (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    group_id   BIGINT       NOT NULL COMMENT 'QQ 群号',
    name       VARCHAR(128) DEFAULT NULL COMMENT '群名称',
    remark     VARCHAR(128) DEFAULT NULL COMMENT '备注',
    enabled    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=允许 0=忽略',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_id (group_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='允许交互的群白名单';
