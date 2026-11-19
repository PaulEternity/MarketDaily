# MarketDaily 后端

需要 Java 21。将 `JAVA_HOME` 指向 JDK 21，并在 `backend` 目录运行命令。首次运行时，Maven Wrapper 会下载 Maven 和项目依赖。Linux 和 macOS 使用 `./mvnw`。

## 本地开发

在已有的 MySQL 8.0 或 8.4 实例中创建数据库。使用的账号需要对该库有迁移和读写权限。

```sql
CREATE DATABASE IF NOT EXISTS market_daily
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

Spring Boot 默认连接 `127.0.0.1:3306`。需要更改地址、端口或数据库名时，设置 `MYSQL_HOST`、`MYSQL_PORT` 或 `MYSQL_DATABASE`。在当前 PowerShell 会话中设置本机数据库的账号和密码，再启动应用。

```powershell
$env:MYSQL_USERNAME = '<本机 MySQL 用户名>'
$credential = Get-Credential -UserName $env:MYSQL_USERNAME -Message '输入本机 MySQL 密码'
$env:MYSQL_PASSWORD = $credential.GetNetworkCredential().Password
.\mvnw.cmd spring-boot:run
```

应用启动时，Flyway 会创建六张业务表并导入基础术语。访问 `http://localhost:8080/actuator/health`，预期返回 HTTP 200 和 `{"status":"UP"}`。

## 服务器部署

在服务器检出要部署的版本，进入 `backend` 目录。首次部署可用 `install -m 600 .env.example .env` 建立配置，已有 `.env` 时继续使用原文件。填写 `MYSQL_PASSWORD`、`FRED_API_KEY`、`COINGECKO_API_KEY` 和 `MARKET_ADMIN_KEY`。已有 MySQL 数据卷时，MySQL 用户和密码应与原先一致。

```sh
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
curl --fail-with-body "http://127.0.0.1:${BACKEND_PORT:-8080}/actuator/health"
```

Compose 构建后端镜像并启动 MySQL 8.4，数据库数据保存在已有的 `mysql-data` 命名卷。后端和 MySQL 端口都绑定服务器回环地址。如有端口冲突，在 `.env` 修改 `BACKEND_PORT` 或 `MYSQL_PORT`。若修改了后端端口，健康检查命令也使用同一端口。只需启动开发用数据库时，运行 `docker compose up -d --wait mysql`。

更新版本时，在同一部署目录检出新提交，再执行上面的启动命令。若启动失败，使用 `docker compose logs --tail=100 backend mysql` 查看原因。

## 手动采集与查询行情

启动后端前，将 `MARKET_ADMIN_KEY`、`FRED_API_KEY` 和 `COINGECKO_API_KEY` 设置到后端进程的环境。Gold API 无需密钥。未配置的行情来源会显示采集失败，其他来源仍会执行。`.env.example` 列出了配置项，直接启动 Spring Boot 时需将它们注入进程环境。

```sh
curl --fail-with-body -X POST http://localhost:8080/api/admin/markets/collect \
  -H "X-Admin-Key: ${MARKET_ADMIN_KEY}"
curl --fail-with-body http://localhost:8080/api/markets/latest
curl --fail-with-body 'http://localhost:8080/api/markets/XAUUSD/history?days=7'
```

最新行情用 `freshness` 标识当前报价、旧值或缺失，用 `collectionStatus` 标识当天是否采集成功。美股以实际交易日期为准，黄金比较前后两次日度采样，BTC 和 ETH 使用来源的 24 小时变化。历史接口支持 `days=7` 或 `days=30`，数据从已采集快照累积，日期缺口会保留。

## 验证

```powershell
.\mvnw.cmd clean verify
```

默认运行无需数据库的 HTTP 冒烟测试。验证迁移和术语查询时，使用一个新建的测试库，并在设置本机数据库连接环境变量后运行以下命令。

```powershell
$env:MYSQL_DATABASE = 'market_daily_test'
$env:MARKETDAILY_DB_TEST = 'true'
.\mvnw.cmd clean verify
Remove-Item Env:MARKETDAILY_DB_TEST, Env:MYSQL_DATABASE
```
