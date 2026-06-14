@echo off
:: Feed lines from a source file to a target file one at a time with randomized jitter.
:: Usage: feed-log.bat <source> <target> [min_ms] [max_ms]
::   min_ms  minimum delay between lines in milliseconds (default: 200)
::   max_ms  maximum delay between lines in milliseconds (default: 2000)

setlocal enabledelayedexpansion

if "%~2"=="" (
    echo Usage: %~nx0 ^<source^> ^<target^> [min_ms] [max_ms] >&2
    exit /b 1
)

set "SOURCE=%~1"
set "TARGET=%~2"
set "MIN_MS=%~3"
set "MAX_MS=%~4"

if "%MIN_MS%"=="" set "MIN_MS=200"
if "%MAX_MS%"=="" set "MAX_MS=2000"

if not exist "%SOURCE%" (
    echo Error: source file '%SOURCE%' not found >&2
    exit /b 1
)

if %MIN_MS% geq %MAX_MS% (
    echo Error: min_ms must be less than max_ms >&2
    exit /b 1
)

set /a "RANGE=MAX_MS - MIN_MS"

for /f "usebackq delims=" %%L in ("%SOURCE%") do (
    echo %%L >> "%TARGET%"
    set /a "DELAY_MS=MIN_MS + !RANDOM! %% RANGE"
    powershell -NoProfile -Command "Start-Sleep -Milliseconds !DELAY_MS!"
)

endlocal
