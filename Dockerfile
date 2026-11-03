# syntax=docker/dockerfile:1.7

ARG BUILD_IMAGE=maven:3.9.15-eclipse-temurin-17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre
ARG ARTIFACT_STAGE=build

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
  mvn -q -DskipTests dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
  mvn -q -DskipTests package \
  && mkdir -p /artifacts \
  && cp target/schema-validation-micro-integration.jar /artifacts/ \
  && javac src/docker/Healthcheck.java -d /tmp/healthcheck \
  && jar --create --file /artifacts/healthcheck.jar -C /tmp/healthcheck Healthcheck.class

# Release bundles include these already-verified Java 17 artifacts. BuildKit
# skips the Maven stage when ARTIFACT_STAGE=packaged is selected.
FROM scratch AS packaged
COPY lib/ /artifacts/

FROM ${ARTIFACT_STAGE} AS artifacts

FROM ${RUNTIME_IMAGE} AS runtime
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="Schema Validation Micro-Integration" \
      org.opencontainers.image.source="https://github.com/solacese/schema-validation-micro-integration" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
WORKDIR /app

ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

COPY --from=artifacts --chown=65532:65532 /artifacts/schema-validation-micro-integration.jar app.jar
COPY --from=artifacts --chown=65532:65532 /artifacts/healthcheck.jar healthcheck.jar

USER 65532:65532
EXPOSE 8088
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=5 \
  CMD ["java", "-cp", "/app/healthcheck.jar", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.config.additional-location=optional:file:/app/config/"]
