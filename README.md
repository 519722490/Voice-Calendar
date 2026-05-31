# Voice Calendar

语音日历工具，支持用户登录、日程 CRUD、重复日程规则、语音识别、以及基于 Agent 的自然语言日程管理。

## 技术栈

### 后端

| 技术 | 用途 |
|---|---|
| Java 21 | 后端主要开发语言 |
| Spring Boot 3.5 | REST API、WebSocket、服务启动与依赖管理 |
| Spring Web | 提供用户、日程、Agent 等 HTTP 接口 |
| Spring WebSocket | 接收前端实时音频流，推送语音识别结果 |
| Spring Data JPA | 操作日程、重复日程、用户等数据库表 |
| Spring Security | 接口鉴权与登录用户上下文管理 |
| JWT | 登录态认证，前端请求通过 Bearer Token 访问接口 |
| PostgreSQL | 业务数据持久化，存储用户、普通日程、重复日程规则 |
| Spring AI Alibaba DashScope | 接入通义千问等大模型，实现 Agent 意图解析与工具调用 |
| DashScope fun-asr-realtime | 实时语音识别，将前端 PCM 音频流转成文本 |
| H2 | 后端测试环境内存数据库 |

### 前端

| 技术 | 用途 |
|---|---|
| Vue 3 | 前端 UI 框架 |
| TypeScript | 类型约束，提升可维护性 |
| Vite | 前端开发服务器与构建工具 |
| Vue Router | 登录页、日历页路由管理 |
| Pinia | 认证、日历、语音/Agent 状态管理 |
| Axios | 统一封装后端 HTTP 请求 |
| Web Audio API | 采集麦克风音频并转换为 PCM 帧 |
| WebSocket | 将实时音频帧发送到后端，并接收识别结果 |
| lucide-vue-next | 前端图标 |

## 项目结构

```text
Voice Calendar
├─ backend                 Spring Boot 后端
│  ├─ config               本地私有配置，不提交 Git
│  └─ src
├─ frontend                Vue 前端
│  ├─ src/api              Axios 与接口基础配置
│  ├─ src/components       页面组件
│  ├─ src/router           Vue Router
│  ├─ src/stores           Pinia 状态管理
│  ├─ src/utils            工具函数
│  └─ src/views            页面级组件
├─ docs                    技术方案文档
└─ sql                     PostgreSQL 初始化脚本
```

## 快速启动

### 1. 环境要求

- Java 21
- Maven 3.9+
- Node.js 20+
- PostgreSQL 16+
- DashScope API Key，使用 Agent 或语音识别时需要

### 2. 准备数据库

如果本地没有 PostgreSQL，可以用 Docker 快速启动：

```bash
docker run -d \
  --name voice-calendar-postgres \
  -e POSTGRES_DB=voice_calendar \
  -e POSTGRES_USER=voice_calendar \
  -e POSTGRES_PASSWORD=123456 \
  -p 5432:5432 \
  postgres:16
```

创建数据库容器后，执行初始化 SQL：

```bash
docker exec -i voice-calendar-postgres psql -U voice_calendar -d voice_calendar < sql/init.sql
```

Windows PowerShell：

```powershell
Get-Content .\sql\init.sql | docker exec -i voice-calendar-postgres psql -U voice_calendar -d voice_calendar
```

如果使用已有服务器数据库，只需要把后端配置中的数据库地址改为服务器地址，例如：

```properties
spring.datasource.url=jdbc:postgresql://121.40.210.76:5432/voice_calendar
```

更多说明见 `sql/README.md`。

### 3. 配置后端

在 `backend/config/application-local.properties` 中填写本地私有配置。该文件已被 `.gitignore` 忽略，不要提交 API Key。

```properties
voice-calendar.ai.enabled=true
spring.ai.dashscope.enabled=true
spring.ai.dashscope.agent.enabled=false
spring.ai.dashscope.api-key=你的DashScopeKey
spring.ai.dashscope.chat.options.model=qwen-plus
spring.ai.dashscope.chat.options.temperature=0.2

voice-calendar.speech.enabled=true
voice-calendar.speech.provider=dashscope
voice-calendar.speech.api-key=你的DashScopeKey
voice-calendar.speech.model=fun-asr-realtime
voice-calendar.speech.endpoint=wss://dashscope.aliyuncs.com/api-ws/v1/inference
voice-calendar.speech.sample-rate=16000
voice-calendar.speech.format=pcm

spring.datasource.url=jdbc:postgresql://localhost:5432/voice_calendar
spring.datasource.username=voice_calendar
spring.datasource.password=123456
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
```

### 4. 启动后端

```bash
cd backend
mvn spring-boot:run
```

后端默认地址：

```text
http://localhost:8080
```

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认地址：

```text
http://localhost:5173
```

如果后端不是 `localhost:8080`，可以在启动前端前配置：

```bash
VITE_API_BASE_URL=http://你的后端地址:8080 npm run dev
```

Windows PowerShell：

```powershell
$env:VITE_API_BASE_URL="http://你的后端地址:8080"
npm run dev
```

## 生产部署

### 后端打包部署

```bash
cd backend
mvn clean package -DskipTests
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

推荐在服务器上使用环境变量注入敏感配置：

```bash
export VOICE_CALENDAR_DB_URL=jdbc:postgresql://127.0.0.1:5432/voice_calendar
export VOICE_CALENDAR_DB_USERNAME=voice_calendar
export VOICE_CALENDAR_DB_PASSWORD=123456
export VOICE_CALENDAR_AUTH_JWT_SECRET=请替换成至少32字节的Base64密钥
export VOICE_CALENDAR_AI_ENABLED=true
export SPRING_AI_DASHSCOPE_ENABLED=true
export SPRING_AI_DASHSCOPE_API_KEY=你的DashScopeKey
export VOICE_CALENDAR_SPEECH_ENABLED=true
export VOICE_CALENDAR_SPEECH_API_KEY=你的DashScopeKey
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

当前项目也支持通过 `backend/config/application-local.properties` 读取本地配置，部署时二选一即可。

### 前端打包部署

```bash
cd frontend
npm install
npm run build
```

构建产物在：

```text
frontend/dist
```

可以将 `frontend/dist` 部署到 Nginx、宝塔、静态网站服务或对象存储中。

如果生产后端地址不是默认值，构建前设置：

```bash
VITE_API_BASE_URL=https://你的后端域名 npm run build
```

Windows PowerShell：

```powershell
$env:VITE_API_BASE_URL="https://你的后端域名"
npm run build
```

## 常用命令

### 后端测试

```bash
cd backend
mvn test
```

### 前端构建检查

```bash
cd frontend
npm run build
```

## 核心功能

- 用户注册、登录、JWT 鉴权
- 用户日程数据隔离
- 普通日程新增、查询、修改、删除
- 重复日程规则存储与动态实例展示
- 前端录音并通过 WebSocket 发送 PCM 音频流
- DashScope 实时语音识别
- Agent 根据自然语言执行日程管理
- 审查模式：操作前需要确认
- 自动模式：高置信度单次日程可直接执行，周期日程会被拦截

## Agent 模式对比

| 模式 | 入口 | 执行方式 | 适合场景 |
|---|---|---|---|
| 审查模式 | 语音识别文本提交 | 先解析意图，再生成待确认操作，用户确认后执行 | 多指令、重复日程、需要人工确认的增删改操作 |
| 自动模式 | 静音自动提交语音文本 | 高置信度单次日程直接执行；不确定或周期日程直接失败 | 快速添加、查询、明确的单次日程操作 |
| AI 助手模式 | 侧边悬浮助手 | 支持对话和记忆；查询可直接回答，增删改需在聊天中确认 | 连续对话、上下文指代、复杂日程管理 |

## 注意事项

- 不要把 DashScope API Key、数据库密码提交到 GitHub。
- 语音识别需要浏览器允许麦克风权限。
- 重复日程第一版保存规则，不会展开成大量普通日程。
- 自动模式不会直接创建或删除重复日程，避免误操作。
