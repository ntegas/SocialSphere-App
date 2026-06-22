#!/bin/sh
#
# Gradle startup script for UN*X
#

APP_HOME="$(dirname "$(realpath "$0")")"
APP_NAME="Gradle"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec java $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
