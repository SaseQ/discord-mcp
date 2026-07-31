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
#
# The download directory is created here for the same reason plus one more: Docker seeds a
# fresh named volume from whatever is at the mount point in the image, ownership included.
# Without this the volume would arrive root-owned and every download would fail as `app`.
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /app/target/logs /var/lib/discord-mcp/downloads \
    && chown -R app:app /app/target /var/lib/discord-mcp
USER app

# Note: this image targets the default stdio transport (no listening port), so the HTTP
# port/healthcheck have been removed. For HTTP mode, re-add EXPOSE 8085 and a healthcheck.

ENTRYPOINT ["java", "-jar", "app.jar"]
