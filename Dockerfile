FROM eclipse-temurin:17-jre
WORKDIR /app
ARG JAR_FILE
COPY --chown=10001:10001 --chmod=0444 ${JAR_FILE} /app/application.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
USER 10001
ENTRYPOINT ["java","-jar","/app/application.jar"]
