# syntax=docker/dockerfile:1.7

# ---------- Build stage ----------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Wrapper + build scripts first so the dep-resolution layer caches independently
# of source changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# Pre-warm dependencies. `--no-daemon` because we exit the container immediately;
# the cache mount is what gives us speed across rebuilds.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test

# Explode the layered jar so Docker can cache deps separately from app code.
RUN mkdir -p /workspace/extracted && \
    cd /workspace/extracted && \
    java -Djarmode=layertools -jar /workspace/build/libs/*.jar extract

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Non-root user; SQLite needs write access to /app/data only.
RUN groupadd --system --gid 1001 sda && \
    useradd --system --uid 1001 --gid sda --home /app --shell /usr/sbin/nologin sda && \
    mkdir -p /app/data && chown -R sda:sda /app

USER sda

# Order matters: least-changing layer first.
COPY --from=build --chown=sda:sda /workspace/extracted/dependencies/ ./
COPY --from=build --chown=sda:sda /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=sda:sda /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=sda:sda /workspace/extracted/application/ ./

VOLUME ["/app/data"]
EXPOSE 8080

ENV JAVA_OPTS="" \
    SPRING_DATASOURCE_URL="jdbc:sqlite:/app/data/graph.db"

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
