# SQL 初始化说明

这里放项目最小可交付的 PostgreSQL 初始化脚本，用来让其他人快速复现数据库表结构。

## 1. 启动 PostgreSQL

```bash
docker run -d \
  --name voice-calendar-postgres \
  -e POSTGRES_DB=voice_calendar \
  -e POSTGRES_USER=voice_calendar \
  -e POSTGRES_PASSWORD=123456 \
  -p 5432:5432 \
  postgres:16
```

## 2. 执行初始化 SQL

Windows PowerShell：

```powershell
Get-Content .\sql\init.sql | docker exec -i voice-calendar-postgres psql -U voice_calendar -d voice_calendar
```

macOS / Linux：

```bash
docker exec -i voice-calendar-postgres psql -U voice_calendar -d voice_calendar < sql/init.sql
```

如果使用的是远程 PostgreSQL，可以在安装了 `psql` 的机器上执行：

```bash
psql "postgresql://voice_calendar:123456@数据库地址:5432/voice_calendar" -f sql/init.sql
```

## 3. 配置后端连接

复制后端环境变量模板：

```bash
cp backend/.env.example backend/.env
```

Windows PowerShell：

```powershell
Copy-Item backend\.env.example backend\.env
```

然后在 `backend/.env` 中配置：

```properties
VOICE_CALENDAR_DB_URL=jdbc:postgresql://localhost:5432/voice_calendar
VOICE_CALENDAR_DB_USERNAME=voice_calendar
VOICE_CALENDAR_DB_PASSWORD=123456
SPRING_JPA_HIBERNATE_DDL_AUTO=update
```

当前是最小可交付方案，所以仍保留 `ddl-auto=update`，方便开发阶段自动补齐字段。后续项目稳定后，可以再升级为 Flyway 版本化迁移。
