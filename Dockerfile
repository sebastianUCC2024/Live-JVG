# syntax=docker/dockerfile:1
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /build
ARG SERVICE_MODULE
# Cachea dependencias antes de copiar el código fuente.
# Cada microservicio conserva su propio ciclo Maven.
COPY services/${SERVICE_MODULE}/pom.xml /build/services/${SERVICE_MODULE}/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -f services/${SERVICE_MODULE}/pom.xml dependency:go-offline
COPY . /build
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -f services/${SERVICE_MODULE}/pom.xml verify \
    && cp services/${SERVICE_MODULE}/target/*-SNAPSHOT.jar /build/application.jar

FROM eclipse-temurin:17-jre-alpine AS runtime-base
# Ejecuta Java con usuario sin privilegios y artefactos de solo lectura.
RUN apk add --no-cache curl \
    && addgroup -g 10001 app && adduser -D -u 10001 -G app app
WORKDIR /app
COPY --chmod=0555 scripts/docker/java-entrypoint.sh /app/java-entrypoint.sh
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
# El entrypoint valida las opciones JVM; JAVA_OPTS conserva compatibilidad.
USER 10001:10001
ENTRYPOINT ["/app/java-entrypoint.sh"]

# Target opcional para utilizar el JAR compilado en el host.
FROM runtime-base AS prebuilt
ARG JAR_FILE
COPY --chown=10001:10001 --chmod=0444 ${JAR_FILE} /app/application.jar

# El target predeterminado compila y prueba el servicio antes de copiar su JAR.
FROM runtime-base AS runtime
COPY --from=build --chown=10001:10001 --chmod=0444 /build/application.jar /app/application.jar
