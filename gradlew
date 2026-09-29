#!/bin/sh
# Lightweight first-run bootstrap: downloads the OFFICIAL Gradle wrapper JAR
# and checks its published SHA-256. Once installed, uses standard GradleWrapperMain.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  if command -v gradle >/dev/null 2>&1; then
    echo 'Generating official Gradle wrapper via installed Gradle...' >&2
    (cd "$APP_HOME" && gradle wrapper --gradle-version 8.14.3 --distribution-type bin)
  else
    sh "$APP_HOME/scripts/bootstrap-wrapper.sh"
  fi
fi
JAVA=java
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; fi
exec "$JAVA" -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
