# --- Stage 1: Build Spring Boot JAR ---
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

COPY pom.xml ./
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# --- Stage 2: Runtime Image ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Cài đặt docker-cli, docker-cli-buildx, git và curl cho các tác vụ runtime (build/run container, git fetch, healthcheck)
RUN apk add --no-cache docker-cli docker-cli-buildx git curl

COPY --from=builder /app/target/*.jar app.jar

ENV DOCKER_BUILDKIT=1
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
