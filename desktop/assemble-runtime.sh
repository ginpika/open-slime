#!/usr/bin/env bash
# macOS / Linux：品牌同步 → 前端构建 → 内含前端的后端 JAR → 当前平台的 JRE。
# 用法：bash desktop/assemble-runtime.sh [--skip-frontend] [--skip-jre]
# 仅改 Rust 壳或 desktop/ui 时无需组装，直接 npm run dev。
set -euo pipefail

desktop_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd -- "$desktop_dir/.." && pwd)"
runtime_dir="$desktop_dir/runtime"
skip_frontend=false
skip_jre=false

usage() {
    cat <<'EOF'
用法：bash desktop/assemble-runtime.sh [--skip-frontend] [--skip-jre]
  --skip-frontend  复用已有 frontend/dist，仅重新打包后端和 JRE
  --skip-jre       复用已有 runtime/jre，通常与 --skip-frontend 一起使用
  -h, --help      显示帮助
需要 Node/npm 和 JDK 21+；JAVA_HOME 可显式选择 JDK。
生成的 JRE 仅用于当前操作系统和 CPU 架构，请在目标平台组装。
EOF
}

fail() { printf '错误：%s\n' "$*" >&2; exit 1; }

for arg in "$@"; do
    case "$arg" in
        --skip-frontend) skip_frontend=true ;;
        --skip-jre) skip_jre=true ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; fail "未知参数：$arg" ;;
    esac
done

for tool in node npm; do
    command -v "$tool" >/dev/null || fail "找不到 $tool，请先安装并加入 PATH"
done

# macOS 的 /usr/bin/java 可能只是系统入口；Homebrew JDK 不一定注册到 java_home。
# 显式 JAVA_HOME 优先，否则优先选择项目使用的 JDK 21。
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then
    detected_jdk="$(/usr/libexec/java_home -F -v 21 2>/dev/null || true)"
    if [[ -z "$detected_jdk" ]] && command -v brew >/dev/null; then
        brew_jdk="$(brew --prefix openjdk@21 2>/dev/null || true)"
        if [[ -n "$brew_jdk" && -x "$brew_jdk/libexec/openjdk.jdk/Contents/Home/bin/javac" ]]; then
            detected_jdk="$brew_jdk/libexec/openjdk.jdk/Contents/Home"
        fi
    fi
    if [[ -n "$detected_jdk" ]]; then export JAVA_HOME="$detected_jdk"; fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    [[ -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/javac" ]] || fail "JAVA_HOME 不是有效的 JDK：$JAVA_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v javac >/dev/null || fail "找不到 javac，请安装 JDK 21+ 或设置 JAVA_HOME"
javac_version="$(javac -version 2>&1)"
java_major="$(printf '%s\n' "$javac_version" | awk '{split($2, v, "."); print v[1]}')"
[[ "$java_major" =~ ^[0-9]+$ ]] && (( java_major >= 21 )) || fail "需要 JDK 21+，当前为 ${javac_version}；请设置 JAVA_HOME"
printf '使用 JDK：%s（%s）\n' "${JAVA_HOME:-PATH}" "$javac_version"
if ! "$skip_jre"; then
    command -v jlink >/dev/null || fail "找不到 jlink，请使用完整 JDK"
elif [[ ! -x "$runtime_dir/jre/bin/java" ]]; then
    fail "runtime/jre/bin/java 不存在，不能 --skip-jre；去掉该开关重跑"
fi
if "$skip_frontend" && [[ ! -f "$root_dir/frontend/dist/index.html" ]]; then
    fail "frontend/dist 不存在，不能 --skip-frontend；去掉该开关重跑"
fi

node "$root_dir/scripts/brand-sync.mjs"
slug="$(node -e 'const b = require(process.argv[1]); if (!/^[a-z0-9][a-z0-9-]*$/.test(b.slug)) process.exit(1); process.stdout.write(b.slug)' "$root_dir/branding.json")"
jar_name="$slug-backend.jar"

if "$skip_frontend"; then
    printf '== 1/3 跳过前端构建（复用 frontend/dist）==\n'
else
    printf '== 1/3 构建前端 ==\n'
    npm --prefix "$root_dir/frontend" run build
fi

printf '== 2/3 打包后端（-Pdesktop 会把前端产物拷进 JAR）==\n'
(
    cd -- "$root_dir/backend"
    # 仓库中的 mvnw 未设置可执行位，使用 sh 调用。
    sh ./mvnw -q -B -Pdesktop -DskipTests package
)

# 由 pom.xml 的固定 version 得到本次构建产物，避免误取 target 中其他版本的旧 JAR。
backend_version="$(node -e 'const fs = require("node:fs"); const xml = fs.readFileSync(process.argv[1], "utf8").replace(/<parent>[\s\S]*?<\/parent>/, ""); const version = xml.match(/<version>([^<]+)<\/version>/)?.[1]; if (!version) process.exit(1); process.stdout.write(version)' "$root_dir/backend/pom.xml")"
backend_jar="$root_dir/backend/target/$slug-backend-$backend_version.jar"
[[ -f "$backend_jar" ]] || fail "打包后未找到 $backend_jar"
mkdir -p -- "$runtime_dir"

if "$skip_jre"; then
    printf '== 3/3 跳过 jlink（保留 runtime/jre）==\n'
else
    printf '== 3/3 生成 jlink 运行时 ==\n'
    # 与 Windows 脚本保持相同模块集。先生成再替换，jlink 失败时保留旧运行时。
    staging_dir="$(mktemp -d "$runtime_dir/.jlink.XXXXXX")"
    trap 'rm -rf -- "$staging_dir"' EXIT
    modules='java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.jfr,jdk.unsupported'
    jlink --add-modules "$modules" --strip-debug --no-header-files --no-man-pages \
        --compress=zip-6 --output "$staging_dir/jre"
    "$staging_dir/jre/bin/java" -version
    rm -rf -- "$runtime_dir/jre"
    mv -- "$staging_dir/jre" "$runtime_dir/jre"
fi

# jlink 会保留只读许可证文件；Tauri 的资源复制沿用这些权限，下一次构建就
# 无法覆盖 target 中的同名文件。给运行时文件补上所有者写权限，保留执行位。
chmod -R u+w "$runtime_dir/jre"

cp -- "$backend_jar" "$runtime_dir/$jar_name"
for old_jar in "$runtime_dir/"*-backend.jar; do
    if [[ -f "$old_jar" && "$old_jar" != "$runtime_dir/$jar_name" ]]; then
        printf '清理旧名 JAR：%s\n' "$old_jar"
        rm -- "$old_jar"
    fi
done
printf '\n完成：%s\n  %s\n  JRE 已就绪\n接着在 desktop/ 执行：npm run dev\n' "$runtime_dir" "$jar_name"
