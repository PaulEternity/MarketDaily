# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
# 如构建环境提供 Maven settings，以构建密钥临时挂载。
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /build/target/marketdaily-*.jar /app/marketdaily.jar
# 应用仅需要读取 JAR，以非 root 用户启动。
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/marketdaily.jar"]
