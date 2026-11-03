@echo off
rem Bootstrap wrapper: Gradle 9.8.2 on JDK 25. No gradle-wrapper.jar needed.
setlocal
set "APP_HOME=%~dp0"
set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.4"
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem Real wrapper present? Use it.
if exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  "%JAVA_HOME%\bin\java.exe" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
  exit /b %ERRORLEVEL%
)

set "GV=9.8.2"
if "%GRADLE_USER_HOME%"=="" set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "GDIR=%GRADLE_USER_HOME%\bootstrap"
set "GBIN=%GDIR%\gradle-%GV%\bin\gradle.bat"

if not exist "%GBIN%" (
  echo Downloading Gradle %GV% ...
  if not exist "%GDIR%" mkdir "%GDIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GV%-bin.zip' -OutFile '%GDIR%\gradle-%GV%.zip'; Expand-Archive -Force '%GDIR%\gradle-%GV%.zip' '%GDIR%'"
  if not exist "%GBIN%" ( echo Gradle download failed & exit /b 1 )
)

pushd "%APP_HOME%"
call "%GBIN%" %*
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
