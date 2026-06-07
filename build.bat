@echo off
REM 构建脚本 - Windows
REM 用法:
REM   build.bat --deepseek-key sk-xxx --siliconflow-key sk-yyy
REM   build.bat --deepseek-key sk-xxx
REM   build.bat

setlocal enabledelayedexpansion

set DEEPSEEK_KEY=
set SILICONFLOW_KEY=

:parse_args
if "%~1"=="" goto done_parsing
if "%~1"=="--deepseek-key" (
    set DEEPSEEK_KEY=%~2
    shift
    shift
    goto parse_args
)
if "%~1"=="--siliconflow-key" (
    set SILICONFLOW_KEY=%~2
    shift
    shift
    goto parse_args
)
if "%~1"=="--help" goto show_help
if "%~1"=="-h" goto show_help
shift
goto parse_args

:show_help
echo 用法: build.bat [选项]
echo.
echo 选项:
echo   --deepseek-key ^<key^>      设置 DeepSeek API Key
echo   --siliconflow-key ^<key^>   设置硅基流动 API Key
echo   -h, --help               显示帮助
echo.
echo 示例:
echo   build.bat --deepseek-key sk-abc123 --siliconflow-key sk-xyz789
echo   build.bat --deepseek-key sk-abc123
exit /b 0

:done_parsing

echo =========================================
echo   AiChat 构建脚本
echo =========================================
echo.
if defined DEEPSEEK_KEY (
    echo DeepSeek Key:    (已设置^)
) else (
    echo DeepSeek Key:    (未设置^)
)
if defined SILICONFLOW_KEY (
    echo SiliconFlow Key: (已设置^)
) else (
    echo SiliconFlow Key: (未设置^)
)
echo.

REM Build
call gradlew.bat assembleDebug ^
    -PdeepseekKey="%DEEPSEEK_KEY%" ^
    -PsiliconflowKey="%SILICONFLOW_KEY%"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo 构建失败!
    exit /b 1
)

REM Get timestamp
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do set datetime=%%I
set TIMESTAMP=%datetime:~0,8%-%datetime:~8,4%

set OUTPUT=AiChat-v1.0-%TIMESTAMP%.apk
copy app\build\outputs\apk\debug\app-debug.apk "%OUTPUT%"

echo.
echo =========================================
echo   构建完成
echo =========================================
echo.
echo 输出文件: %OUTPUT%
echo.

endlocal
