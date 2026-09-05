#!/bin/sh
APP_HOME=$(cd "${0%/*}" && pwd -P)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ]; then
  GRADLE_JAVA="$JAVA_HOME/bin/java"
else
  GRADLE_JAVA=java
fi
exec "$GRADLE_JAVA" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
