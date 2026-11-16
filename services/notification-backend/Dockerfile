# DEV 1 must provide an approved Java 17 Linux runtime image (preferably by digest).
# No default image is selected or downloaded by this service's preparation phase.
ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}

WORKDIR /app
COPY --chown=10001:10001 target/notification-backend-0.0.1-SNAPSHOT.jar /app/notification.jar
USER 10001:10001

ENV NOTIFICATION_BIND_ADDRESS=0.0.0.0 \
    NOTIFICATION_STORAGE_ENABLED=false \
    NOTIFICATION_MESSAGING_ENABLED=false

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/notification.jar"]
