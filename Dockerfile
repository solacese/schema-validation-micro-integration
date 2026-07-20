# syntax=docker/dockerfile:1.7

ARG BUILD_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
  mvn -q -DskipTests dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
  mvn -q -DskipTests package \
  && javac src/docker/Healthcheck.java -d /tmp/healthcheck \
  && jar --create --file /tmp/healthcheck/healthcheck.jar -C /tmp/healthcheck Healthcheck.class

FROM ${RUNTIME_IMAGE} AS runtime
WORKDIR /app

ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

COPY --from=build --chown=65532:65532 /workspace/target/schema-validation-micro-integration-1.0.0-SNAPSHOT.jar app.jar
COPY --from=build --chown=65532:65532 /tmp/healthcheck/healthcheck.jar healthcheck.jar

USER 65532:65532
EXPOSE 8088
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=5 \
  CMD ["java", "-cp", "/app/healthcheck.jar", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.config.additional-location=optional:file:/app/config/"]
