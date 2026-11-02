# Humanoid Workspace

这个目录是一个工作区，包含多个独立应用：

| 目录 | 说明 |
|---|---|
| `Humanoid-spring/` | Spring Boot 后端 + Vue 管理台 + Docker Compose（QQ AI 机器人） |
| `Humanoid-flutter/` | Flutter 客户端（预留，尚未初始化） |

## 启动后端 / 机器人

```bash
cd Humanoid-spring
docker compose up -d --build
```

- 管理台：http://localhost:8080/admin/
- LLBot WebUI：http://localhost:3080
- OneBot HTTP：http://localhost:3000

详细配置、数据目录和运维说明见 [`Humanoid-spring/README.md`](Humanoid-spring/README.md)。
