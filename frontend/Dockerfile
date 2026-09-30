# minis 前端镜像：Vite 构建 → nginx 托管静态产物 + 反代 /api
# 构建上下文：仓库根目录（docker compose 已配置）——branding.json 在根目录，供 vite.config 读取
FROM node:22-alpine AS build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# 放到 /build，使 vite.config 的 ../branding.json 解析到它
COPY branding.json /build/branding.json
RUN npm run build

FROM nginx:1.27-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/frontend/dist /usr/share/nginx/html
EXPOSE 80
