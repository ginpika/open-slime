#!/usr/bin/env bash
# 组装桌面端运行时（macOS/Linux）：前端构建 → 后端 jar（内含前端产物）→ jlink 裁剪 JRE。
# 产物落在 desktop/runtime/，由 tauri.conf.json 的 bundle.resources 随应用分发。
# 用法：在 desktop/ 目录执行  bash scripts/assemble-runtime.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
desktop="$(cd "$here/.." && pwd)"
root="$(cd "$desktop/.." && pwd)"
runtime="$desktop/runtime"

# 品牌名以根 branding.json 为准：先同步静态配置，再取 slug 拼出 jar 名
node "$root/scripts/brand-sync.mjs"
slug="$(node -p "require('$root/branding.json').slug")"

echo "== 1/3 构建前端 =="
(cd "$root/frontend" && npm run build)

echo "== 2/3 打包后端（-Pdesktop 会把前端产物拷进 jar 的 static/）=="
(cd "$root/backend" && ./mvnw -q -B -Pdesktop -DskipTests package)

echo "== 3/3 生成 jlink 运行时 =="
rm -rf "$runtime"
mkdir -p "$runtime"

# 模块集覆盖 Spring Boot(web/jdbc/logging)、sqlite-jdbc、OkHttp、sshj、docker-java 的实际依赖
modules="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,\
java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,\
java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.jfr,jdk.unsupported"

jlink --add-modules "$modules" --strip-debug --no-header-files --no-man-pages \
      --compress=zip-6 --output "$runtime/jre"

jar_path="$(ls "$root"/backend/target/$slug-backend-*.jar | grep -v '\.original$' | head -n 1)"
cp "$jar_path" "$runtime/$slug-backend.jar"

echo "完成：$runtime"
echo "接着在 desktop/ 执行：npm install && npm run dev"
