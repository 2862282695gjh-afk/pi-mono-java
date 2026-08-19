# CampusClaw Agent
# Multi-stage Maven build for a single application container.

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Avoid JIT instruction incompatibilities when this image is built through a
# local cross-architecture container runtime.
ENV MAVEN_OPTS="-Xint"

# Copy Maven descriptors first for better layer caching.
COPY pom.xml .
COPY codecheck.xml .
COPY modules/agent-core/pom.xml modules/agent-core/pom.xml
COPY modules/coding-agent-cli/pom.xml modules/coding-agent-cli/pom.xml
COPY modules/ai/pom.xml modules/ai/pom.xml
COPY modules/tui/pom.xml modules/tui/pom.xml
COPY modules/cron/pom.xml modules/cron/pom.xml

# Copy modules
COPY modules ./modules

# Build the application
RUN mvn -B -DskipTests -Dcheckstyle.skip=true -Dspotless.check.skip=true -Djacoco.skip=true \
    -pl modules/coding-agent-cli -am package

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

# Copy the built JAR
COPY --from=builder /app/modules/coding-agent-cli/target/campusclaw-agent.jar app.jar

# Create workspace directory
RUN mkdir -p /workspace

# Environment variables
ENV SPRING_PROFILES_ACTIVE=k8s
ENV WORKSPACE_PATH=/workspace
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"

# Expose ports
EXPOSE 8080 9249

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
