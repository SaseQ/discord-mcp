FROM docker.io/library/maven:3.9.6-amazoncorretto-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM docker.io/library/amazoncorretto:17-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

ENV DISCORD_TOKEN=""
ENV DISCORD_GUILD_ID=""

# Run as an unprivileged user so a compromised dependency can't act as root in the container.
# Pre-create the log directory and hand ownership to the app user, since /app is root-owned.
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /app/target/logs \
    && chown -R app:app /app/target
USER app

EXPOSE 8085

# No HEALTHCHECK here: stdio mode has no HTTP endpoint. docker-compose.yml defines one for HTTP mode.

ENTRYPOINT ["java", "-jar", "app.jar"]
