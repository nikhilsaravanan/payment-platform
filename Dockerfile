# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY ledger-contract ledger-contract
COPY ledger-service ledger-service
COPY payment-service payment-service
ARG SERVICE
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl ${SERVICE} -am package -DskipTests
RUN cp ${SERVICE}/target/*-exec.jar /service.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build --chown=app:app /service.jar /app/service.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/service.jar"]
