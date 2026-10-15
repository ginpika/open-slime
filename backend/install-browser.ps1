$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & .\mvnw.cmd -q dependency:build-classpath '-Dmdep.outputFile=target/browser-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve Playwright dependencies.' }
    $browserClasspath = (Get-Content -LiteralPath target/browser-classpath.txt -Raw).Trim()
    & java -cp $browserClasspath com.microsoft.playwright.CLI install chromium
    if ($LASTEXITCODE -ne 0) { throw 'Chromium installation failed.' }
} finally { Pop-Location }
