# minis 后端镜像（仅 Spring Boot jar；前端由独立 nginx 容器托管）
# 构建上下文：仓库根目录（docker compose 已配置）——branding.json 在根目录，需随上下文进镜像
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# branding.json 放在 /build，pom 的 resources 会以 ${project.basedir}/.. 引用到它
COPY branding.json ./
COPY backend/pom.xml ./backend/
WORKDIR /build/backend
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q -DskipTests package

# 运行：仅需 JRE（glibc 基础镜像，兼容 sqlite-jdbc 原生库）
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/backend/target/*.jar app.jar

# 数据统一落盘到 /data（SQLite 库 + markdown 文章目录），由 compose 卷挂载持久化
ENV MINIS_DB_PATH=/data/minis.db \
    MINIS_ARTICLE_DIR=/data/article

EXPOSE 18081
VOLUME ["/data"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
