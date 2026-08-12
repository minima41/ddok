@ECHO OFF
SETLOCAL
SET APP_HOME=%~dp0
SET JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
IF NOT EXIST "%JAR%" (
  ECHO Downloading Gradle wrapper...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '%APP_HOME%gradle\wrapper' | Out-Null; Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar' -OutFile '%JAR%'"
  IF ERRORLEVEL 1 EXIT /B 1
)
java -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
ENDLOCAL
