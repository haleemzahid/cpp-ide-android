@echo off
REM Convenience wrapper: AGP 8.x needs JDK 17+, but your PATH has JDK 11 first.
REM This script pins JAVA_HOME to Adoptium 21 and forwards args to gradlew.
REM
REM Usage:
REM   run.bat assembleDebug
REM   run.bat installDebug
REM   run.bat buildNativeHello
REM   run.bat clean
REM
REM Edit JAVA_HOME below if your JDK lives somewhere else.

setlocal
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JAVA_HOME does not point to a valid JDK:
    echo   %JAVA_HOME%
    echo Edit run.bat and set JAVA_HOME to your JDK 17+ installation.
    exit /b 1
)
echo Using JAVA_HOME=%JAVA_HOME%
call gradlew.bat %*
endlocal
