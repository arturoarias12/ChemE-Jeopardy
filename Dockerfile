# File: Dockerfile
# Description: Multi-stage container build for cloud platforms that run Docker images.
# Author: Arturo Arias
# Last updated: 2026-05-04

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY src ./src

# Compile without build.ps1 so Linux-based cloud builders do not need PowerShell.
RUN find src -name "*.java" > sources.txt \
    && javac -d out @sources.txt

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/out ./out
COPY public ./public
COPY data ./data

ENV PORT=8080
EXPOSE 8080

CMD ["java", "-cp", "out", "com.chemejeopardy.Main"]
