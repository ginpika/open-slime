# 组装桌面端运行时（Windows）：品牌同步 → 前端构建 → 后端 jar（内含前端产物）→ jlink 裁剪 JRE。
#
# 产物落在 desktop/runtime/，由 tauri.conf.json 的 bundle.resources 随应用分发；本机模式启动时
# 按 branding.json 的 slug 去找 <slug>-backend.jar 与 jre/bin/javaw.exe（见 src/backend.rs 的
# runtime_dir）。所以改名 slug 之后必须重跑本脚本，否则壳会报"未找到内置后端运行时"。
#
# macOS / Linux 使用同目录的 assemble-runtime.sh，见 README.md。
# 用法（在 desktop/ 目录执行；从别处调用也行，脚本按自身位置解析路径）：
#   powershell -ExecutionPolicy Bypass -File assemble-runtime.ps1
#   powershell -ExecutionPolicy Bypass -File assemble-runtime.ps1 -SkipFrontend
#   powershell -ExecutionPolicy Bypass -File assemble-runtime.ps1 -SkipFrontend -SkipJre
#
#   不带参数              全量：前端 → jar → jre
#   -SkipFrontend         前端产物没动，重打 jar + 重做 jre
#   -SkipFrontend -SkipJre  只重打 jar（保留现成 jre，最快）
#
# 什么时候要跑：改过 branding.json 的 slug / 前端 / 后端 Java。
# 只改 Rust 壳或 desktop/ui/index.html 不用跑，直接 `npm run dev`（tauri dev）即可。
[CmdletBinding()]
param(
    # 跳过前端构建，复用已有的 frontend/dist（改了 frontend/ 就别跳）
    [switch]$SkipFrontend,
    # 不重做 jlink 裁剪的 JRE，保留 runtime/jre（改了 jlink 模块集或换了 JDK 就别跳）
    [switch]$SkipJre
)

$ErrorActionPreference = "Stop"

$desktop = Resolve-Path $PSScriptRoot
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$frontend = Join-Path $root "frontend"
$backend = Join-Path $root "backend"
$runtime = Join-Path $desktop "runtime"

# 品牌名以根 branding.json 为准：先同步静态配置，再取 slug 拼出 jar 名
node (Join-Path $root "scripts/brand-sync.mjs")
if ($LASTEXITCODE -ne 0) { throw "brand-sync 失败（退出码 $LASTEXITCODE）" }
$slug = (Get-Content (Join-Path $root "branding.json") -Raw | ConvertFrom-Json).slug
if ([string]::IsNullOrWhiteSpace($slug)) { throw "branding.json 里没读到 slug" }
$jarName = "$slug-backend.jar"

# 1/3 前端：产物进 frontend/dist，下一步由 -Pdesktop 拷进 jar 的 static/
if ($SkipFrontend) {
    if (-not (Test-Path (Join-Path $frontend "dist\index.html"))) {
        throw "frontend/dist 不存在，不能 -SkipFrontend；去掉该开关重跑"
    }
    Write-Host "== 1/3 跳过前端构建（复用 frontend/dist）=="
} else {
    Write-Host "== 1/3 构建前端 =="
    Push-Location $frontend
    try {
        npm run build
        if ($LASTEXITCODE -ne 0) { throw "前端构建失败（退出码 $LASTEXITCODE）" }
    } finally { Pop-Location }
}

# 2/3 后端：-Pdesktop 会把前端 dist 拷进 jar 的 static/，--server.port=0 由壳启动时给
Write-Host "== 2/3 打包后端（-Pdesktop 会把前端产物拷进 jar 的 static/）=="
Push-Location $backend
try {
    & .\mvnw.cmd -q -B -Pdesktop -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "后端打包失败（退出码 $LASTEXITCODE）" }
} finally { Pop-Location }

if (-not (Test-Path $runtime)) { New-Item -ItemType Directory -Force -Path $runtime | Out-Null }

# 3/3 jlink：只重建 runtime/jre 这一层，不动同目录下已就位的 jar
if ($SkipJre) {
    Write-Host "== 3/3 跳过 jlink（保留 runtime/jre）=="
} else {
    Write-Host "== 3/3 生成 jlink 运行时 =="
    $jre = Join-Path $runtime "jre"
    if (Test-Path $jre) { Remove-Item -Recurse -Force $jre }

    # 模块集覆盖 Spring Boot(web/jdbc/logging)、sqlite-jdbc、OkHttp、sshj、docker-java 的实际依赖
    $modules = @(
        "java.base", "java.compiler", "java.desktop", "java.instrument", "java.logging",
        "java.management", "java.naming", "java.net.http", "java.prefs", "java.rmi",
        "java.scripting", "java.security.jgss", "java.security.sasl", "java.sql",
        "java.sql.rowset", "java.transaction.xa", "java.xml", "java.xml.crypto",
        "jdk.crypto.ec", "jdk.jfr", "jdk.unsupported"
    ) -join ","

    jlink --add-modules $modules --strip-debug --no-header-files --no-man-pages `
          --compress=zip-6 --output $jre
    if ($LASTEXITCODE -ne 0) { throw "jlink 失败（退出码 $LASTEXITCODE，需 PATH 里有 jlink）" }
}

# 取刚打出的 fat jar（排除 Spring Boot 的 .original），按当前 slug 落到运行时目录
$jar = Get-ChildItem (Join-Path $backend "target\$slug-backend-*.jar") |
       Where-Object { $_.Name -notlike "*.original" } |
       Sort-Object LastWriteTime -Descending |
       Select-Object -First 1
if (-not $jar) { throw "没找到 target\$slug-backend-*.jar，打包步骤没成功？" }
Copy-Item $jar.FullName (Join-Path $runtime $jarName) -Force

# 清掉别的 slug 留下的旧 jar：壳按 <slug> 拼名字找 jar，留着同名以外的包只会造成
# 「runtime 里明明有 backend 却说未找到」的误判
Get-ChildItem $runtime -Filter "*-backend.jar" |
    Where-Object { $_.Name -ne $jarName } |
    ForEach-Object {
        Write-Host "== 清理旧名 jar：$($_.Name)（当前 slug 是 $slug）=="
        Remove-Item $_.FullName -Force
    }

Write-Host ""
Write-Host "完成：$runtime"
Write-Host ("  {0}  {1} MB" -f $jarName, [math]::Round((Get-Item (Join-Path $runtime $jarName)).Length / 1MB, 1))
Write-Host ("  jre  {0}" -f $(if (Test-Path (Join-Path $runtime "jre")) { "已就绪" } else { "缺失（去掉 -SkipJre 重跑）" }))
Write-Host "接着在 desktop/ 执行：npm run dev"
