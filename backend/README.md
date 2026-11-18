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

## 服务器的 Docker MySQL

将 `.env.example` 复制为 `.env`，填写独立的应用账号和密码。默认应用账号是 `marketdaily`。如服务器的 3306 端口已被占用，更改 `MYSQL_PORT`。

```sh
docker compose up -d --wait
```

Compose 会初始化 MySQL 8.4，并在命名卷中保留数据。数据库端口仅绑定服务器的回环地址。后端在服务器上直接运行时，从进程环境读取同样的账号、密码和端口。当前 Compose 服务只有 MySQL。

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
