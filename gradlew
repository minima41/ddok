#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "Downloading Gradle wrapper..."
  mkdir -p "$(dirname "$JAR")"
  curl -fL "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar" -o "$JAR"
fi
exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
