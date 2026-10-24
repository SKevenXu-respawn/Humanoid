# Humanoid

一个运行在 QQ 上的 AI 聊天机器人：通过 LLBot（OneBot 11）接收和发送 QQ 消息，使用 DeepSeek V4.1 Flash 生成回复，支持上下文记忆、工具调用、任务、提醒、主动发言、技能、文件和图片处理。

## 功能特性

| 模块 | 说明 |
|---|---|
| 私聊 / 群聊 | 白名单控制，群聊需单独登记；非白名单私聊会收到一次提示（带冷却） |
| 上下文记忆 | 最近 100 条原文进入主 AI；每 100 条由子 AI 合并成滚动总结 |
| 多模态 | 支持接收图片并在上下文中理解；历史图片可按需回看 |
| 语音消息 | 收到语音后调用豆包录音文件识别转成文字，再交给主 AI 聊天；失败时回退 LLBot 自带转写 |
| 工具调用 | Spring AI 工具框架；图片上下文走原生 HTTP 时也支持完整工具循环；调用时向用户同步“开始/第 N 步/长时间处理中”进度 |
| 发送能力 | 文本、图片、系统表情、收藏表情、文件、多条消息、SVG 绘图、语音（豆包 TTS） |
| 联网 | 网页抓取（SmartWebFetch），可选 Brave 搜索 |
| 浏览器 | Playwright MCP（无头 Chromium）：打开网页、点击、填表、截图、读取页面 |
| 任务 | 多步骤任务，后台执行器逐步执行，失败可暂停/恢复 |
| 提醒 | AI 设置定时提醒；到点把预设提示词交给主 AI 执行（可调工具）并回复 |
| 主动行为 | 每 30 分钟自主决定：什么都不做 / 找用户聊天 / 刷视频并留评论 |
| 好友管理 | 管理台查看好友、删除好友（删除前自动发送告知消息）、同意/拒绝好友申请、处理被过滤的好友申请（通过 LLBot 接口） |
| 管理台 | 内网 Web 管理：白名单、上下文/总结、任务、提醒、好友、概览 |
| 技能 | Markdown 技能库，AI 可创建并立即热加载 |
| 文件沙箱 | 按 QQ 号隔离的私有目录，`skills/`、`tools/` 共享 |
| 运维 | 工具调用日志、最近调用注入、危险工具开关 |

## 架构

```
QQ 客户端 (pmhq 容器, Xvfb)
        │
        ▼
LLBot (OneBot 11, :3080 WebUI / :3000 HTTP API)
        │  webhook POST /api/llbot/events
        ▼
spring-app (:8080)
  ├─ BotEventService   白名单校验、引用消息解析、入库
  ├─ AiChatService     组装系统提示词 + 总结 + 上下文 + 工具
  ├─ SummaryService    滚动总结（子 AI）
  ├─ ProactiveService  主动行为（聊天 / 刷视频并评论）
  ├─ ReminderService   定时提醒派发
  ├─ TaskExecutionService 后台任务执行
  └─ 工具层            文件/联网/消息/任务/技能/SVG/Shell/浏览器(Playwright MCP)
        │
        ▼
MySQL (:3306) + DeepSeek API
```

### 一次消息的处理流程

| 1. LLBot 通过 HTTP POST 推送事件到 `/api/llbot/events`
2. 校验私聊用户白名单、群白名单；解析引用消息原文；语音消息异步转写为文字
3. 写入 `bot_conversation`（审计）和 `bot_context`（AI 上下文）
4. `AiChatService` 异步生成回复：
   - 纯文本：Spring AI `ChatClient`（支持工具）
   - 上下文含图片：原生 DeepSeek HTTP（多模态 + 工具循环）
5. 通过 LLBot API 发送回复，并写入上下文

定时提醒到点时走同一条 AI 链路：先把预设提示词作为一条用户消息写入上下文，再交给 `AiChatService` 处理，因此提醒可以触发联网、浏览器、文件等工具，而不是直接发送固定文本。

### AI 上下文构成

```
系统提示词 (prompts/system-prompt.txt)
+ 滚动总结 (bot_summary)
+ 文件目录信息（当前用户目录 + 共享目录）
+ 当前任务进度 (bot_task / bot_task_step)
+ 可用技能清单
+ 最近 N 次工具调用 (bot_tool_call)
—— 以上拼入 system 消息 ——
+ 最近 100 条上下文消息 (bot_context)
   - 最近 8 条消息内的图片（最多 4 张）以 base64 附带
```

## 技术栈

- Java 17 / Spring Boot 4.1.1（Spring Framework 7、Tomcat 11）
- Spring AI 2.0.1（DeepSeek，文本工具调用）
- spring-ai-agent-utils 0.13.0（WebFetch、TodoWrite、可选 Brave 搜索）
- Spring AI MCP Client（接入 Playwright MCP）
- MyBatis-Plus 3.5.17（Boot 4 starter）
- MySQL 8.0
- Gson（JSON；Boot 4 默认 Jackson 3，本项目通过配置强制使用 Gson）
- Apache Batik 1.20（SVG → PNG）
- Vue 3 + Vite + Element Plus（管理台前端）
- LLBot + PMHQ（QQNT 协议，Linux 容器）

## 管理台

构建后访问 **http://localhost:8080/admin/**（Hash 路由，例如 `#/dashboard`）。

一期功能：
- 概览：白名单、对话、上下文、任务、提醒、图片、工具调用统计，LLBot 状态
- 用户 / 群白名单：搜索、新增、编辑、启用开关、删除
- 上下文与总结：按会话查看消息与图片、编辑/删除滚动总结、清空上下文
- 任务：新建、步骤管理、开始/暂停/恢复/完成/删除
- 提醒：新建（填写到点后交给主 AI 的提示词）、取消、失败重试
- 好友管理：好友列表与删除、好友申请同意/拒绝、被过滤好友申请处理
- 语音设置：开关 `sendVoice`、选择豆包 TTS 2.0 音色，保存后立即生效

> 好友申请依赖 OneBot 的 `request/friend` 事件：应用收到事件后写入 `bot_friend_request`，管理台再通过 `set_friend_add_request` 处理。若非白名单私聊只收到提示、没有 AI 回复，好友申请仍会正常记录。

本地开发：

```bash
./mvnw spring-boot:run          # 后端 8080
cd frontend && npm install && npm run dev   # 前端 5173，代理 /api
```

> ⚠️ 管理台**没有登录**，仅供内网使用。请通过防火墙/反向代理限制来源，或把 compose 中的端口改为 `127.0.0.1:8080:8080`。

## 快速开始

### 前置条件

- Docker / Docker Compose
- 已获取 LLBot 的 `auth_token`（https://auth.luckylillia.com）
- DeepSeek API Key
- （可选）豆包语音合成 API Key，用于 `sendVoice` 工具

### 启动

1. 创建 `.env`（已被 `.gitignore` 忽略）：

   ```properties
   PMHQ_AUTH_TOKEN=你的LLBot token
   DEEPSEEK_API_KEY=你的DeepSeek Key
   VOICE_API_KEY=你的豆包语音合成Key
   ```

2. 构建并启动：

   ```bash
   docker compose up -d --build
   ```

3. 首次登录：

   - 打开 LLBot WebUI：http://localhost:3080
   - 密码在 `llbot_config/webui_token.txt`（未配置时以容器日志为准）
   - 扫码登录 QQ；OneBot 11 HTTP 服务端已配置为 `0.0.0.0:3000`

4. 登记白名单：

   ```sql
   -- 允许某个用户
   INSERT INTO bot_user (user_id, nickname, enabled) VALUES (123456789, '昵称', 1);

   -- 允许某个群
   INSERT INTO bot_group (group_id, name, enabled) VALUES (987654321, '群名', 1);
   ```

5. DeepSeek Key 由 `.env` 中的 `DEEPSEEK_API_KEY` 注入（`application.properties` 中为 `${DEEPSEEK_API_KEY:}`），也可以在 compose 里直接覆盖该环境变量。

### 端口

| 端口 | 服务 |
|---|---|
| 8080 | Spring 应用 |
| 3080 | LLBot WebUI |
| 3000 | OneBot 11 HTTP API |
| 3306 | MySQL（root/root，仅建议开发使用） |
| 13000 | PMHQ（仅容器内网） |

## 数据目录与初始化（不纳入 Git）

以下目录都是运行时数据，已在 `.gitignore` 中排除。克隆仓库后需要自行创建，或由容器首次启动时生成。

### `.env`

```properties
PMHQ_AUTH_TOKEN=你的LLBot token
DEEPSEEK_API_KEY=你的DeepSeek Key
```

- LLBot token：https://auth.luckylillia.com
- local 运行（`mvnw spring-boot:run`）会通过 `spring.config.import` 自动读取 `.env`
- Docker 运行由 `docker-compose.yml` 读取并注入

### `bot_files/` — AI 文件沙箱

```bash
mkdir -p bot_files/skills bot_files/tools bot_files/users
```

```
bot_files/
├── skills/            # 共享技能（Markdown + frontmatter），可直接放入 .md
├── tools/             # 共享脚本/工具
└── users/<QQ号>/      # 每个用户的私有工作区（运行后自动创建）
    └── downloads/
```

- 挂载：`./bot_files:/app/files`
- 权限：容器内可读写
- 备份：整个目录拷走即可；不建议提交到 Git（含用户文件、下载内容、图片）

### `llbot_config/` — LLBot 运行数据

- 挂载：`./llbot_config:/app/llbot/data`
- 首次 `docker compose up` 时由 LLBot 自动生成，**无需手动创建**
- 目录内容：

| 文件/目录 | 说明 |
|---|---|
| `auth_token.txt` | LLBot 鉴权 token，也可以用环境变量 `PMHQ_AUTH_TOKEN` |
| `config_<QQ号>.json` | OneBot 配置（HTTP 服务、WebHook 等） |
| `webui_token.txt` | WebUI 登录密码 |
| `database/` | OneBot 的消息/好友缓存 |
| `logs/`、`temp/` | 运行日志与临时文件 |

- 备份此目录可以保留 LLBot 配置和 WebUI 密码

### `llonebot/` — 本地 LLBot 发行包

- 约 176MB，包含 `LuckyLillia` 可执行文件、`start.sh`、说明文档
- **只用于在宿主机直接运行 LLBot**，Docker 部署不需要
- 已排除出 Git，避免仓库体积膨胀

### `playwright_output/` — 浏览器输出目录

- 挂载：Playwright 容器 `/app/output` ↔ 宿主机 `./playwright_output` ↔ spring-app `/app/mcp-output`
- Playwright MCP 生成的截图会先写到这里，随后被移动到当前用户的 `downloads/screenshots/`
- 已排除出 Git，首次启动自动创建

### Docker 命名卷

| 卷 | 用途 | 备份方式 |
|---|---|---|
| `qq_volume` | QQ 客户端登录态 | `docker run --rm -v humanoid_qq_volume:/data -v %cd%:/backup alpine tar czf /backup/qq_volume.tar.gz -C /data .` |
| `mysql_data` | MySQL 数据 | `mysqldump -h127.0.0.1 -uroot -proot humanoid > backup.sql` |

### 从零安装

1. 克隆仓库
2. 创建 `bot_files/skills`、`bot_files/tools`（可选放入初始技能）
3. 创建 `.env` 并填入 `PMHQ_AUTH_TOKEN`、`DEEPSEEK_API_KEY`、`VOICE_API_KEY`（语音工具可选）
4. 启动（首次会拉取 Playwright MCP 镜像，体积较大）：

   ```bash
   docker compose up -d --build
   ```

5. 等待 `pmhq` 健康检查通过，打开 LLBot WebUI（http://localhost:3080）扫码登录 QQ
6. 在 MySQL 中登记用户/群白名单
7. （可选）在 `application.properties` 填 `humanoid.tools.brave-api-key` 后重新构建，启用联网搜索

## 关键配置

`src/main/resources/application.properties`：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `spring.ai.deepseek.api-key` | `${DEEPSEEK_API_KEY:}` | 从环境变量读取 |
| `spring.ai.deepseek.chat.options.model` | `deepseek-flash` | V4.1-Flash，支持图片 |
| `humanoid.ai.thinking` | `max` | 原生通道思考等级：disabled/low/high/max |
| `humanoid.ai.summary-threshold` | 100 | 每多少条对话合并一次总结 |
| `humanoid.ai.context-messages` | 100 | 主 AI 携带的原文上下文条数 |
| `humanoid.ai.context-image-window-messages` | 8 | 只有最近 N 条内的图片会附带 |
| `humanoid.ai.max-images` | 4 | 单次请求最多图片数 |
| `humanoid.ai.recent-tool-calls` | 20 | 注入系统提示词的最近工具调用数 |
| `humanoid.ai.max-tool-rounds` | -1 | 原生通道工具循环轮数，-1 表示不限制（硬保护 1000 轮） |
| `humanoid.ai.tool-progress.enabled` | true | 工具调用时向用户发送进度提示 |
| `humanoid.ai.tool-progress.min-interval-seconds` | 8 | 两条进度消息的最小间隔 |
| `humanoid.ai.tool-progress.max-messages` | 8 | 单个回合最多发送的进度消息数 |
| `humanoid.ai.tool-progress.heartbeat-seconds` | 25 | 单个工具执行超过该秒数时补发“还在处理中”，0 关闭 |
| `humanoid.voice.api-key` | `${VOICE_API_KEY:}` | 豆包语音合成 Key，从环境变量读取 |
| `humanoid.voice.enabled` | true | 语音发送默认开关（管理端保存后会以数据库配置为准） |
| `humanoid.voice.resource-id` | `seed-tts-2.0` | TTS 模型版本；克隆音色用 `seed-icl-2.0` |
| `humanoid.voice.speaker` | `zh_female_jiaochuannv_uranus_bigtts` | 默认音色（娇喘女声 2.0） |
| `humanoid.voice.max-audio-bytes` | 10485760 | 下载音频大小上限 |
| `humanoid.asr.enabled` | true | 语音消息转文字开关 |
| `humanoid.asr.api-key` | `${VOICE_API_KEY:}` | 语音识别 Key（默认复用语音合成 Key） |
| `humanoid.asr.resource-id` | `volc.bigasr.auc_turbo` | 录音文件识别极速版资源 ID |
| `humanoid.asr.timeout-seconds` | 30 | 单次识别超时 |
| `humanoid.asr.max-audio-bytes` | 10485760 | 参与识别的音频大小上限 |
| `spring.ai.tools.limits.max-total-tool-calls` | -1 | Spring AI 文本通道工具调用总数，-1 不限制 |
| `spring.ai.tools.limits.max-calls-per-tool-default` | -1 | 单个工具调用次数，-1 不限制 |
| `humanoid.tools.file-base-dir` | `/app/files` | 文件沙箱根目录 |
| `humanoid.tools.skills-dir` | `${file-base-dir}/skills` | 共享技能目录 |
| `humanoid.tools.shell.enabled` | true | Shell 工具开关（高风险） |
| `spring.ai.mcp.client.enabled` | `${MCP_ENABLED:true}` | 是否启用 MCP 客户端 |
| `spring.ai.mcp.client.streamable-http.connections.playwright.url` | `http://playwright:8931` | Playwright MCP 地址（仅容器内网） |
| `humanoid.tools.mcp-output-dir` | `/app/mcp-output` | spring-app 中共享输出目录的挂载点 |
| `humanoid.tools.playwright-output-dir` | `/app/output` | Playwright 容器内的输出目录 |
| `humanoid.proactive.*` | cron `0 */30 * * * *` | 每 30 分钟自主行为；check-interval 冷却 25 分钟 |
| `humanoid.reminder.*` | 见文件 | 提醒派发间隔与重试次数 |
| `humanoid.task.executor.*` | 见文件 | 后台任务执行器 |

## 数据库

启动时自动执行 `src/main/resources/db/schema.sql`（`CREATE TABLE IF NOT EXISTS`）。

| 表 | 用途 |
|---|---|
| `bot_user` | 用户白名单 |
| `bot_group` | 群白名单 |
| `bot_context` | 主 AI 上下文消息（含图片引用） |
| `bot_summary` | 每个会话的滚动总结 |
| `bot_conversation` | 完整对话审计 |
| `bot_tool_call` | 工具调用日志 |
| `bot_image` | 已发送图片的 base64 副本 |
| `bot_task` / `bot_task_step` | 任务与步骤 |
| `bot_reminder` | 定时提醒（content 为交给主 AI 的预设提示词） |
| `bot_voice_config` | 语音发送开关与音色（管理端修改，单行） |
| `bot_friend_request` | 好友申请（OneBot request/friend 事件） |
| `bot_proactive_state` | 主动发言状态 |

## 文件沙箱

```
/app/files/
├── skills/            # 共享技能（Markdown + frontmatter）
├── tools/             # 共享脚本/工具
└── users/<QQ号>/      # 每个用户的私有工作区
    └── downloads/     # 下载文件
```

- 相对路径默认解析到用户目录
- `skills/xxx`、`tools/xxx` 访问共享目录
- 越界路径、符号链接逃逸会被拒绝
- Shell 工具的工作目录和 `HOME` 都是用户私有目录

## 工具清单

| 工具 | 说明 |
|---|---|
| `currentTime` | 当前时间 |
| `readFile` / `writeFile` / `editFile` | 沙箱文件读写 |
| `listDirectory` / `grep` / `glob` | 目录与内容检索 |
| `runShell` | 沙箱内执行 shell（有超时、输出限制、危险命令拦截） |
| `downloadFile` | 下载文件（SSRF 防护） |
| `sendImage` / `sendFace` / `getFavoriteFaces` | 发送图片、表情、收藏表情 |
| `sendFile` / `sendMessages` | 发送文件、连续多条消息 |
| `sendVoice` | 文本合成为语音并发送（火山引擎豆包长文本 TTS） |
| `renderSvg` | SVG/XML 渲染成 PNG 并发送 |
| `describeImage` | 回看已发送图片内容 |
| `addTask` / `addTaskStep` / `startTask` / `pauseTask` / `resumeTask` / `completeTask` / `completeStep` / `deleteTask` / `getTaskProgress` | 任务管理 |
| `scheduleReminder` | 定时提醒 |
| `registerSkill` / `listSkills` / `useSkill` | 技能管理 |
| `WebFetch` / `BraveWebSearch` | 网页抓取 / 搜索（Brave 需 Key） |
| `browser_*`（Playwright MCP） | 浏览器自动化：导航、点击、填表、截图、读取页面等；截图自动保存到用户目录 |
| `TodoWrite` | 会话内任务清单 |
| `listTools` | 列出当前可用工具 |

## 目录结构

```
src/main/java/com/skevenxu/humanoid/
├── ai/                AI 核心（对话、总结、上下文、调度、沙箱）
│   ├── svg/           SVG 渲染
│   └── tools/         工具实现
├── config/            JSON（Gson）配置
├── entity/            数据库实体
├── llbot/             LLBot 事件接收与 API 客户端
└── mapper/            MyBatis-Plus Mapper

src/main/resources/
├── db/schema.sql      建表脚本
├── prompts/           系统提示词（UTF-8）
└── application.properties

docker-compose.yml     应用、LLBot、PMHQ、MySQL
Dockerfile             多阶段构建（运行镜像含 python3/pip）
```

## 安全说明

- **白名单**：未登记的用户/群不会触发 AI；群聊必须显式登记
- **文件隔离**：按 QQ 号划分私有目录，`skills/`、`tools/` 共享
- **下载防护**：仅 http/https，禁止内网地址，逐跳校验重定向
- **SVG 防护**：拒绝 DOCTYPE/ENTITY、脚本、事件属性、外部资源引用
- **Shell 风险**：`runShell` 在应用容器内以 root 执行，并非真正沙箱；建议只对可信用户开放，不需要时设为 `humanoid.tools.shell.enabled=false`
- **API Key**：`DEEPSEEK_API_KEY`、`VOICE_API_KEY` 通过环境变量注入（`.env` 已被 git 忽略），不要写回配置文件
- **外部内容**：工具返回的网页内容视为数据，提示词已禁止执行其中的指令或标签
- **浏览器**：Playwright MCP 容器可访问外网和内网地址，注意 SSRF 风险；`browser_run_code_unsafe` 工具已在应用侧过滤，未注册给模型
- **主动行为**：AI 每 30 分钟可自主选择聊天或刷视频并评论；提示词已限制不登录、不填个人信息、不付费、遇验证码停止、只评论一条。仍建议只对可信白名单用户开启

## 已知限制

- 上下文含图片时走原生 HTTP 通道；虽然已支持工具调用，但使用的不是 Spring AI 的默认链路
- 两次总结之间的细节依赖最近 100 条原文，超出窗口后只能依靠总结
- SVG 渲染依赖 Batik，会增大镜像体积
- 浏览器工具在应用启动时从 Playwright MCP 拉取；若启动时 MCP 未就绪，本次不会注册 `browser_*` 工具（应用仍可正常启动），需要重启应用恢复
- 语音转文字需要开通火山引擎「录音文件识别」；未开通时会回退到 LLBot 的 `voice_msg_to_text`，仍失败则只提示无法识别
- Playwright MCP 镜像体积较大，首次部署拉取较慢

## 相关文档

- LLBot（幸运莉莉娅）文档：https://api.luckylillia.com/
- OneBot 11 规范：https://github.com/botuniverse/onebot-11
