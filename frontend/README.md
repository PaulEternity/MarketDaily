# MarketDaily 前端

需要 Node.js 22.12 或更高版本。在前端目录执行：

```sh
npm ci
npm run dev
```

在浏览器打开终端显示的 Vite 地址。开发和预览默认将同源 `/api` 请求代理至 `http://127.0.0.1:8080`，可通过 `MARKETDAILY_API_TARGET` 更改后端地址。

运行 `npm run build` 生成 `dist`。托管静态文件时，把同域的 `/api/` 路径转发给 Spring Boot。页面依赖行情和新闻查询接口，刷新只重新读取已经入库的数据。
