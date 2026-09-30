@echo off
chcp 65001 >nul
cd /d "%~dp0"
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":18081 .*LISTENING"') do (
    echo Kill 占用 18081 的进程 PID=%%p
    taskkill /f /pid %%p >nul 2>&1
)
call mvnw.cmd spring-boot:run
pause
