@echo off
setlocal
set "JAVA_EXEC=java.exe"
if defined JAVA_HOME set "JAVA_EXEC=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXEC%" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
