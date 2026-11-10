FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package && \
    cp "$(find target -maxdepth 1 -name '*.jar' ! -name '*.original' | head -n 1)" app.jar

FROM eclipse-temurin:17-jre

WORKDIR /app
ENV SERVER_PORT=9000 \
    API_CONTEXT_PATH=/mapi \
    JITSI_MEET_DB_URL=jdbc:sqlite:/app/data/jitsi-meet-api.sqlite.db

RUN mkdir -p /app/data
COPY --from=build /workspace/app.jar /app/app.jar

EXPOSE 9000
VOLUME ["/app/data"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
