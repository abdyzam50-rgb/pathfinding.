#!/bin/sh
#
# Gradle start-up script for POSIX-compatible shell
#

# Attempt to set JAVA_HOME if not already set
if [ -z "$JAVA_HOME" ]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_HOME="$(java -XshowSettings:all -version 2>&1 | sed -n 's/.*java.home = //p')"
  fi
fi

APP_NAME="Gradle"
APP_BASE_NAME="$(basename "$0")"
APP_HOME="$(cd "$(dirname "$0")" && pwd)"

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
JAVACMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"

exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
