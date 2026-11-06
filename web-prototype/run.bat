@echo off
chcp 65001 >nul
cd /d "%~dp0"

where python >nul 2>nul
if %errorlevel%==0 (
    python run.py
    goto :end
)

where py >nul 2>nul
if %errorlevel%==0 (
    py -3 run.py
    goto :end
)

echo.
echo 没有找到 Python。你可以：
echo   1. 安装 Python 3（勾选 Add to PATH），再双击本文件；
echo   2. 或者在本目录手动运行： python run.py
echo.

:end
pause
