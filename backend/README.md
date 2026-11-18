# MarketDaily 后端

使用 Java 21 和 Spring Boot 3.5。将 `JAVA_HOME` 指向 JDK 21，并在 `backend` 目录执行命令。

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

首次运行时，Maven Wrapper 会下载 Maven 和项目依赖。Linux 和 macOS 使用 `./mvnw`。

服务启动后访问 `http://localhost:8080/actuator/health`，预期返回 HTTP 200 和 `{"status":"UP"}`。构建中的冒烟测试会在随机端口验证同一个接口。