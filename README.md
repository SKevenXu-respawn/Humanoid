# Humanoid Workspace

Humanoid 是一个运行在 QQ 上的 AI 聊天机器人项目：通过 LLBot（OneBot 11）接收和发送 QQ 消息，使用 DeepSeek 生成回复，支持上下文记忆、长期记忆、工具调用、定时提醒、后台任务、主动行为以及图片 / 语音 / 文件处理。

仓库当前包含两部分：一个功能较完整的 Spring Boot 后端，以及一个刚起步的 Flutter Windows 桌面客户端。

## 项目组成

| 目录 | 说明 | 状态 | 详情 |
|---|---|---|---|
| `Humanoid-spring/` | Spring Boot 后端 + Vue 管理台 + Docker Compose 编排（QQ AI 机器人） | 可运行，核心功能已完成 | [查看详情](Humanoid-spring/README.md) |
| `Humanoid-flutter/` | Flutter 桌面客户端，仅启用 Windows 平台 | 已初始化，业务功能未开始 | [查看详情](Humanoid-flutter/README.md) |

## 完成状态

### Humanoid-spring（后端 / QQ 机器人）

已完成：

- [x] QQ 接入：LLBot / OneBot 11、私聊与群聊白名单、好友申请处理
- [x] AI 对话：DeepSeek，文本与图片多模态
- [x] 上下文：最近 100 条原文 + 每 100 条由子 AI 合并的滚动总结
- [x] 长期记忆：私有 / 群公共 / 关系记忆，按可见性注入并定期压缩
- [x] 工具调用：文件沙箱、Shell、联网抓取与搜索、Playwright 浏览器、发消息 / 图片 / 表情 / 文件 / 语音、SVG、技能
- [x] 任务与提醒：后台逐步执行、失败暂停与恢复；定时提醒可触发完整 AI 链路
- [x] 主动行为：每 30 分钟自主决定聊天 / 刷视频，视频进入分享队列
- [x] 语音：豆包录音文件识别转文字 + 豆包 TTS 语音回复
- [x] 管理台：白名单、上下文、记忆、提示词、任务、提醒、好友、视频队列、语音、密钥
- [x] 部署：`docker compose` 一键启动应用 / LLBot / QQ 客户端 / MySQL / Playwright
- [x] 数据库：启动时按 `schema.sql` 自动创建 20 张表

未完成 / 待完善：

- [ ] 管理台登录鉴权（当前无登录，仅建议在内网使用）
- [ ] 含图片的消息统一走 Spring AI 工具链（当前走原生 HTTP 实现）
- [ ] Playwright MCP 晚于应用启动时自动重连（当前需要重启应用才能注册浏览器工具）
- [ ] `runShell` 真正沙箱化（当前在应用容器内以 root 权限执行）
- [ ] 超出上下文窗口的细节保留（当前依赖滚动总结）

### Humanoid-flutter（Windows 客户端）

已完成：

- [x] Flutter 项目初始化（Flutter 3.47.2 / Dart 3.13.2）
- [x] 仅启用 Windows 平台，`flutter analyze` 与 Debug 构建通过

尚未开始（具体功能范围待定）：

- [ ] 客户端页面与功能设计
- [ ] 对接后端接口（聊天、管理数据等；接入前后端需要先加鉴权）
- [ ] 后端地址、密钥等本地配置
- [ ] 打包与分发

> 客户端目前只有 Flutter 默认模板，尚未实现任何 Humanoid 业务功能。

## 启动后端 / 机器人

```bash
cd Humanoid-spring
docker compose up -d --build
```

| 服务 | 地址 |
|---|---|
| 管理台 | http://localhost:8080/admin/ |
| LLBot WebUI | http://localhost:3080 |
| OneBot HTTP | http://localhost:3000 |

## 文档

- 后端详细配置、数据目录与运维说明：[`Humanoid-spring/README.md`](Humanoid-spring/README.md)
- 客户端开发说明：[`Humanoid-flutter/README.md`](Humanoid-flutter/README.md)
- QQ 通信协议参考：[`Humanoid-spring/docs/QQ通信协议API.txt`](Humanoid-spring/docs/QQ通信协议API.txt)
