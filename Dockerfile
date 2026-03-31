FROM maven:3.9.6-amazoncorretto-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM amazoncorretto:17-alpine

RUN apk upgrade --no-cache

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

ENV DISCORD_TOKEN=""
ENV DISCORD_GUILD_ID=""

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "app.jar"]
