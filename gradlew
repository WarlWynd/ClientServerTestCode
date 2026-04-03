#!/bin/sh
# Gradle wrapper script for Unix
GRADLE_WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
GRADLE_WRAPPER_PROPERTIES="gradle/wrapper/gradle-wrapper.properties"
exec java -classpath "$GRADLE_WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
