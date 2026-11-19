#!/bin/sh
set -eu
expected='-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError'
# Rechaza opciones JVM distintas de las previstas; no evalúa texto del usuario.
if [ "${JAVA_OPTS-$expected}" != "$expected" ] ||
   [ -n "${JAVA_TOOL_OPTIONS-}" ] || [ -n "${JDK_JAVA_OPTIONS-}" ] || [ -n "${_JAVA_OPTIONS-}" ]; then
    echo 'Unapproved JVM options refused' >&2
    exit 64
fi
if [ "$#" -ne 0 ]; then
    echo 'Runtime argument overrides refused' >&2
    exit 64
fi
exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -jar /app/application.jar
