# syntax=docker/dockerfile:1

# ----------------------------------------------------------------------
# Build stage — compile the Spring Boot jar with the Maven wrapper.
# ----------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the wrapper + pom first and warm the dependency cache, so code-only
# changes don't re-download every dependency on each build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline || true

# Now the sources, then build (tests are run separately in CI, not here).
COPY src/ src/
RUN ./mvnw -q clean package -DskipTests

# ----------------------------------------------------------------------
# Runtime stage — small JRE image that just runs the jar.
# ----------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /opt/klrams/app

# Storage dirs the app writes to (videos, shapefiles, etc). MOUNT A VOLUME
# over /opt/klrams/data in compose so uploads survive image rebuilds.
# Create an unprivileged user and hand it ownership so the app does not run as
# root (defence in depth: a compromise of the process is not a compromise of the
# container's root).
RUN mkdir -p /opt/klrams/data/videos /opt/klrams/data/shapefiles \
             /opt/klrams/data/excel /opt/klrams/data/images \
             /opt/klrams/data/reports /opt/klrams/data/temp \
 && groupadd -r klrams && useradd -r -g klrams -d /opt/klrams klrams \
 && chown -R klrams:klrams /opt/klrams

COPY --from=build --chown=klrams:klrams /app/target/*.jar app.jar

USER klrams

EXPOSE 8090

# Simple TCP liveness check (temurin base has bash + /dev/tcp).
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8090' || exit 1

# JAVA_OPTS lets you cap heap to the container (see docker-compose.yml).
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
