# 组装桌面端运行时（Windows）：前端构建 → 后端 jar（内含前端产物）→ jlink 裁剪 JRE。
# 产物落在 desktop/runtime/，由 tauri.conf.json 的 bundle.resources 随应用分发。
# 用法：在 desktop/ 目录执行  powershell -ExecutionPolicy Bypass -File scripts/assemble-runtime.ps1
$ErrorActionPreference = "Stop"

$root = Resolve-Path "$PSScriptRoot\..\.."
$desktop = Resolve-Path "$PSScriptRoot\.."
$frontend = Join-Path $root "frontend"
$backend = Join-Path $root "backend"
$runtime = Join-Path $desktop "runtime"

# 品牌名以根 branding.json 为准：先同步静态配置，再取 slug 拼出 jar 名
node "$root/scripts/brand-sync.mjs"
$slug = (Get-Content (Join-Path $root "branding.json") -Raw | ConvertFrom-Json).slug

Write-Host "== 1/3 构建前端 =="
Push-Location $frontend
npm run build
Pop-Location

Write-Host "== 2/3 打包后端（-Pdesktop 会把前端产物拷进 jar 的 static/）=="
Push-Location $backend
.\mvnw.cmd -q -B -Pdesktop -DskipTests package
Pop-Location

Write-Host "== 3/3 生成 jlink 运行时 =="
if (Test-Path $runtime) { Remove-Item -Recurse -Force $runtime }
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

# 模块集覆盖 Spring Boot(web/jdbc/logging)、sqlite-jdbc、OkHttp、sshj、docker-java 的实际依赖
$modules = @(
    "java.base", "java.compiler", "java.desktop", "java.instrument", "java.logging",
    "java.management", "java.naming", "java.net.http", "java.prefs", "java.rmi",
    "java.scripting", "java.security.jgss", "java.security.sasl", "java.sql",
    "java.sql.rowset", "java.transaction.xa", "java.xml", "java.xml.crypto",
    "jdk.crypto.ec", "jdk.jfr", "jdk.unsupported"
) -join ","

jlink --add-modules $modules --strip-debug --no-header-files --no-man-pages `
      --compress=zip-6 --output (Join-Path $runtime "jre")

$jar = Get-ChildItem (Join-Path $backend "target\$slug-backend-*.jar") |
       Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
Copy-Item $jar.FullName (Join-Path $runtime "$slug-backend.jar")

Write-Host "完成：$runtime"
Write-Host "接着在 desktop/ 执行：npm install && npm run dev"
