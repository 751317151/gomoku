# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-11 AS build
WORKDIR /workspace
COPY gomoku-game/gomoku-game/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline
COPY gomoku-game/gomoku-game/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:11-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 gomoku \
    && useradd --system --uid 10001 --gid gomoku --home-dir /app gomoku \
    && mkdir -p /app/data \
    && chown -R gomoku:gomoku /app

WORKDIR /app
COPY --from=build --chown=gomoku:gomoku /workspace/target/gomoku-game-1.0-SNAPSHOT.jar /app/app.jar

USER gomoku
EXPOSE 9000 9001
VOLUME ["/app/data"]

ENV JAVA_OPTS="-Xms128m -Xmx512m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]