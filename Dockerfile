# syntax=docker/dockerfile:1
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /build
ARG SERVICE_MODULE
# Keep the selected descriptor in a source-independent dependency cache layer.
# The selected module has an independent Spring Boot parent and lifecycle.
COPY services/${SERVICE_MODULE}/pom.xml /build/services/${SERVICE_MODULE}/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -f services/${SERVICE_MODULE}/pom.xml dependency:go-offline
COPY . /build
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -f services/${SERVICE_MODULE}/pom.xml verify \
    && cp services/${SERVICE_MODULE}/target/*-SNAPSHOT.jar /build/application.jar

FROM eclipse-temurin:17-jre-alpine AS runtime-base
# Fixed unprivileged identity and read-only application artifacts reduce runtime write access.
RUN apk add --no-cache curl \
    && addgroup -g 10001 app && adduser -D -u 10001 -G app app
WORKDIR /app
COPY --chmod=0555 scripts/docker/java-entrypoint.sh /app/java-entrypoint.sh
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
# The entrypoint enforces its own JVM policy; JAVA_OPTS is retained for image compatibility.
USER 10001:10001
ENTRYPOINT ["/app/java-entrypoint.sh"]

# Optional compatibility target for the existing host-built Spring Boot JAR path.
FROM runtime-base AS prebuilt
ARG JAR_FILE
COPY --chown=10001:10001 --chmod=0444 ${JAR_FILE} /app/application.jar

# Default target builds and tests the selected module inside the JDK stage.
FROM runtime-base AS runtime
COPY --from=build --chown=10001:10001 --chmod=0444 /build/application.jar /app/application.jar
