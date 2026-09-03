# Render has no Java runtime, so the backend ships as an image.
#
# Two stages: Maven builds the fat jar, then a JRE runs it. The build layer is
# discarded, which matters because the Maven image is around 800 MB and the
# runtime is a tenth of that.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Poms first, so a change to source does not re-download the dependency tree.
COPY pom.xml .
COPY engine/pom.xml engine/
COPY domain/pom.xml domain/
COPY api/pom.xml api/
COPY app/pom.xml app/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY engine engine
COPY domain domain
COPY api api
COPY app app

# Tests are skipped on purpose. The integration suite drives a real PostGIS
# container through Testcontainers, and there is no Docker daemon inside a
# build container. Tests run in CI and locally, not here.
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/app/target/app-0.1.0-SNAPSHOT.jar app.jar

# Tuned for a 512 MB instance. MaxRAMPercentage rather than a fixed -Xmx so the
# same image behaves on a larger box. SerialGC because G1's own bookkeeping is
# not worth it at this heap size, and this is a single modest instance.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"

# Both flags are idempotent. SeedLoader checks existsById before every insert and
# SeedCommand returns early when a username is already there, so a restart or a
# redeploy re-runs the checks and writes nothing.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar --seed --demo-users"]
