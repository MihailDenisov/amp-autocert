@echo off
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
if not exist "%WRAPPER_JAR%" (
  where gradle >nul 2>nul
  if not errorlevel 1 (
    pushd "%APP_HOME%"
    call gradle wrapper --gradle-version 8.14.3 --distribution-type bin
    popd
  ) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%APP_HOME%scripts\bootstrap-wrapper.ps1"
    if errorlevel 1 exit /b 1
  )
)
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
