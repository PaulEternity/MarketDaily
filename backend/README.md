# MarketDaily 后端

## 代码目录

从一次 HTTP 请求读代码时，可以先找 `controller` 里的路由，再沿同名业务的 `service` 查处理流程。读写数据库的 SQL 在 `mapper`，响应对象和查询模型在 `model`。外部行情、新闻和 AI 模型的调用在 `client`。

例如，查询行情趋势可以从 `controller/MarketController` 的 `history` 入手，依次查看 `service/market/MarketService`、`mapper/MarketPriceMapper` 和 `model/market/MarketHistory`。

## 本地开发

需要 Java 21。将 `JAVA_HOME` 指向 JDK 21，并在 `backend` 目录运行命令。首次运行时，Maven Wrapper 会下载 Maven 和项目依赖。Linux 和 macOS 使用 `./mvnw`。

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

在服务器检出要部署的版本，进入 `backend` 目录。首次部署可用 `install -m 600 .env.example .env` 建立配置，已有 `.env` 时继续使用原文件。填写 `MYSQL_PASSWORD`、`FRED_API_KEY` 和 `MARKET_ADMIN_KEY`。已有 MySQL 数据卷时，MySQL 用户和密码应与原先一致。

```sh
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
curl --fail-with-body "http://127.0.0.1:${BACKEND_PORT:-8080}/actuator/health"
```

Compose 构建后端镜像并启动 MySQL 8.4，数据库数据保存在已有的 `mysql-data` 命名卷。后端和 MySQL 端口都绑定服务器回环地址。如有端口冲突，在 `.env` 修改 `BACKEND_PORT` 或 `MYSQL_PORT`。若修改了后端端口，健康检查命令也使用同一端口。只需启动开发用数据库时，运行 `docker compose up -d --wait mysql`。

更新版本时，在同一部署目录检出新提交，再执行上面的启动命令。若启动失败，使用 `docker compose logs --tail=100 backend mysql` 查看原因。

## 手动采集与查询行情

启动后端前，将 `MARKET_ADMIN_KEY` 和 `FRED_API_KEY` 设置到后端进程的环境。Gold API 和币安公开行情无需密钥。未配置密钥的 FRED 行情会显示采集失败，其他来源仍会执行。`.env.example` 列出了配置项，直接启动 Spring Boot 时需将它们注入进程环境。

```sh
curl --fail-with-body -X POST http://localhost:8080/api/admin/markets/collect \
  -H "X-Admin-Key: ${MARKET_ADMIN_KEY}"
curl --fail-with-body http://localhost:8080/api/markets/latest
curl --fail-with-body 'http://localhost:8080/api/markets/XAUUSD/history?days=7'
```

最新行情用 `freshness` 标识当前报价、旧值或缺失，用 `collectionStatus` 标识当天是否采集成功。国际黄金（`XAUUSD`）与白银（`XAGUSD`）以美元每金衡盎司计价，比较前后两次同品种日度采样。国内黄金（`AU9999`）使用上海黄金交易所 Au99.99 最近交易日收盘价，单位为人民币每克。国内白银（`AGTD`）使用上海黄金交易所 Ag(T+D) 最近交易日收盘价，单位为人民币每千克。纳指也按实际交易日收盘价展示。比特币使用币安 `BTCUSDT` 现货报价和滚动 24 小时涨跌，以 USDT 计价。原 CoinGecko `BTCUSD` 日线和小时数据可继续按原标的查询，`ETHUSD` 保留日线历史。历史接口支持 `days=7` 或 `days=30`，日期缺口会保留。

国际现货金银、BTC 和国内金银支持 `GET /api/markets/{symbol}/hourly`。每小时第 5 分钟采集国际现货金银和 BTC，按来源时间归档，手动采集也会写入这三项的小时数据。国内金银在上金所常规交易时段采集延时最新价，按北京时间采集小时归档。小时记录自启用后积累。

看板通过 `GET /api/markets/exchange-rate` 单独读取 USD/CNY 参考汇率。优先使用中国银行美元折算价，失败时用已配置的 `FRED_API_KEY` 查询 DEXCHUS。后端对成功结果缓存 30 分钟，暂时失败时可沿用进程内最近 14 天的有效汇率。看板在原币报价下显示国际黄金元/克、国际白银元/千克及 BTC 人民币参考价，BTC 按 `1 USDT ≈ 1 USD` 估算。换算仅用于最新行情展示。

国内金银日收盘价和 FRED 纳斯达克综合指数默认每天北京时间 08:30、18:30 更新。环境变量 `MARKET_HOURLY_CRON` 和 `MARKET_DAILY_CRON` 可覆盖对应的采集时间，设为 `-` 可关闭相应定时任务。

## 手动采集与查询新闻

新闻来源为 GDELT DOC 2.0、美联储货币政策公告 RSS、上海黄金交易所公告（`SGE_NOTICE`）和国家统计局数据发布（`NBS_RELEASE`），均无需单独配置密钥。国内来源保存列表中的标题、原文链接和发布日期。列表只精确到日，按北京时间当天零点参与现有时间窗口查询，前端只展示日期。手动采集复用 `MARKET_ADMIN_KEY`，响应包含实际查询窗口和各来源状态。平日采集 24 小时，周末采集 72 小时。

```sh
curl --fail-with-body -X POST http://localhost:8080/api/admin/news/collect \
  -H "X-Admin-Key: ${MARKET_ADMIN_KEY}"
curl --fail-with-body 'http://localhost:8080/api/news?market=GOLD&page=1&size=20'
```

新闻列表默认读取最近 24 小时。可用 ISO 8601 的 `from`、`to` 指定最多 30 天的窗口，用 `GOLD`、`NASDAQ` 或 `CRYPTO` 筛选市场。`publishedAt` 是已知的发布时间，`seenAt` 是 GDELT 收录时间。相同的 `eventHash` 表示同一事件，条目仍各自保留来源和原文链接。

`NEWS_GDELT_QUERY` 可覆盖检索词。`NEWS_SCORE_WEIGHTS` 依次为相关性、重要性、可信度、时效，默认 `40,30,20,10`，总和须为 100。

配置 `JIN10_MCP_TOKEN` 后，后端会启用金十快讯（`JIN10_FLASH`），用发布时间、标题和原文链接参与同一套新闻筛选。金十每轮最多读取 8 页。资讯密集时，采集窗口内更早的快讯可能超出读取上限。可通过手动采集接口确认 `sources.JIN10_FLASH` 返回的状态和筛选数量。完成验证后，在 `.env` 中设置 `NEWS_GDELT_ENABLED=false` 并重建后端容器，即可停用 GDELT。

黄金宏观新闻另收录欧洲央行 `ECB_POLICY`、日本央行 `BOJ_POLICY`、OPEC 公告 `OPEC_PRESS` 和 EIA 原油周报 `EIA_WEEKLY`。配置金十 Token 后，`JIN10_MACRO` 还会分别搜索原油、OPEC 和主要央行，每个关键词取首屏。官方列表只有日期时按来源时区日首归档。任一来源不可达时，其他新闻来源继续采集。

同一金十 Token 还会启用比特币专题 `JIN10_BTC`，分别检索「比特币」「BTC」「现货比特币ETF」的首屏。手动采集响应中的 `sources.JIN10_BTC` 显示该来源的结果，相关新闻按现有筛选和去重规则进入 `CRYPTO` 新闻和日报候选。

## AI 日报

在 `.env` 设置 `LLM_BASE_URL`、`LLM_API_KEY` 和 `LLM_MODEL` 后，重启后端。硅基流动的 Base URL 填 `https://api.siliconflow.cn/v1`，第一版用 `Qwen/Qwen3.5-4B`。模型未配置时仍可查询既有日报。

先完成一次行情或新闻采集，再从本机执行：

```sh
curl --fail-with-body -X POST http://localhost:8080/api/admin/reports/generate \
  -H "X-Admin-Key: ${MARKET_ADMIN_KEY}"
curl --fail-with-body http://localhost:8080/api/reports/today
curl --fail-with-body 'http://localhost:8080/api/reports?page=1&size=10'
```

生成使用北京时间当天的行情状态和最近 24 小时去重后的新闻。成功后保存证据快照、新闻引用、模型和可用的 Token 用量。同一单实例服务里，一个业务日已有日报时会直接返回旧结果（`reused=true`），不会重新调用模型。详情可用 `GET /api/reports/{yyyy-MM-dd}` 查询。模型失败时可稍后手动重试，接口不会自动重发生成请求。

新版日报的贵金属、纳指和比特币板块分别增加短期走势预判，覆盖下一交易时段、下一交易日和生成后 24 小时。板块缺少当前有效报价时显示证据不足。历史日报按已保存内容展示。

新生成的日报会保存 BIS 主要经济体政策利率和 FRED `DFII10` 最近两条有效观测，包含观测日和来源。`DFII10` 复用已有的 `FRED_API_KEY`；任一数据源暂时不可用时，日报仍可按已有行情和新闻生成。

### 自动日报

配置模型后，将 `MARKETDAILY_REPORT_SCHEDULE_ENABLED` 设为 `true` 并重启后端。直接运行 Spring Boot 时，将该变量注入进程环境。任务按北京时间运行：

- 08:15 采集行情和新闻，记录未更新的行情和失败来源。
- 08:30 使用已有数据生成日报。错过采集时先补采，部分来源失败时仍可使用已入库的证据。
- 09:00 检查当天缺失的日报，全天最多两次自动生成尝试。启动时超过 08:30 会按同一规则检查补跑，已保存日报直接跳过。手动生成仍可通过管理接口触发。

看板通过 `GET /api/reports/schedule-status` 查询自动调度开关，以及最近一次采集和生成的业务日期、尝试次数、执行起止时间、状态和原因。尚未执行的任务返回 `null`。

使用以下 SQL 查询自动任务记录及对应日报的 Token 用量。任务时间戳为 UTC，模型未返回的 Token 用量显示为 `NULL`。

```sql
SELECT t.business_date, t.task_type, t.attempt_no, t.status, t.started_at,
       t.finished_at, t.error_message, t.trace_id, r.input_tokens, r.output_tokens
FROM task_execution t
LEFT JOIN daily_report r ON t.task_type = 'DAILY_REPORT' AND t.status = 'SUCCESS'
    AND t.business_date = r.report_date AND r.status = 'SUCCESS'
ORDER BY t.business_date DESC, t.started_at DESC
LIMIT 30;
```

自动调度按单实例运行。如果模型已经收到请求，但后端在保存日报前退出，下一次尝试仍可能产生一次模型调用。

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

## 黄金走势信号

`GET /api/markets/gold-signals` 返回 Au99.99、XAU/USD 和 COMEX GC 最近 72 个完整自然小时的已入库 K 线、最近七条日线和突破统计。时间点为 UTC，前端转北京时间。旧版小时报价继续用于原行情趋势图。

- Au99.99 默认在北京时间 08:40、18:40 从上金所每日行情补最近 30 天的日 OHLC 和双向成交量（千克）。国内小时线及分时成交量使用授权数据导入。
- XAU/USD 配置 `GOLD_OHLC_API_KEY` 后，每小时第 12 分钟用 [Gold API OHLC](https://gold-api.com/llms.txt) 补最近 3 根已收盘小时线及最近 5 个 UTC 日历日内缺失的工作日线。未配置时仍可导入。国际现货没有全球成交量字段。
- COMEX GC 以授权数据导入，按同一个 UTC 小时对照 XAU/USD。成交量以单向合约手数计。两个定时任务分别用 `GOLD_SGE_DAILY_CRON`、`GOLD_OHLC_CRON` 配置，设为 `-` 可关闭。

把授权行情转换成 JSON 数组后，可以用同一管理密钥导入。每批最多 1000 根，重复导入会更新相同品种、周期、来源和时间的记录。

```sh
curl --fail-with-body -X POST 'http://localhost:8080/api/admin/markets/gold-bars' \
  -H "X-Admin-Key: ${MARKET_ADMIN_KEY}" \
  -H 'Content-Type: application/json' --data-binary @gold-bars.json
```

可保存为 `gold-bars.json` 的数组示例（数字仅用于说明格式）：

```json
[
  {
    "symbol": "GC",
    "period": "HOUR",
    "source": "授权供应商名称",
    "startTime": "2026-11-19T13:00:00Z",
    "tradingDate": "2026-11-19",
    "openPrice": 4000,
    "highPrice": 4010,
    "lowPrice": 3990,
    "closePrice": 4005,
    "volume": 1000,
    "buyVolume": 500,
    "sellVolume": 450
  }
]
```

- `symbol` 为 `AU9999`、`XAUUSD` 或 `GC`。小时线用整点 UTC 桶起点。Au99.99 和 GC 的 `tradingDate` 取源数据的实际交易归属，夜盘尤其不能按显示日期推断。XAU/USD 用 UTC 日期。
- 日线用 `period: "DAY"`，`startTime` 填该交易日期对应的 UTC 午夜作为主键，和实际开盘时间无关。Au99.99 日线须等北京时间 15:30 后发布，其余日线须在该 UTC 日期结束后导入。日线参照取早于当前交易日的最近一条已入库有效日线。
- Au99.99 小时成交量使用单向千克，日成交量使用双向千克，日线不填主动方向。GC 成交量使用单向手数。XAU/USD 不填成交量。方向未知时两个主动量都留空，只有总量和两个方向齐全时才计算余量。
- 同一品种的小时图一次只显示一个来源，当前选择最近一根小时线的来源。补历史时请统一来源名。国内优先用上金所官方日线作参照，国际用同来源日线。日线缺失可能使参照回退到更早的有效交易日。
