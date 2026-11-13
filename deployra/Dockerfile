# syntax=docker/dockerfile:1
# Build (chạy trong thư mục deployra/):  docker build -t deployra-app .

# ---------- Stage 1: build bằng Maven + JDK 21 ----------
    FROM maven:3.9-eclipse-temurin-21 AS build
    WORKDIR /workspace
    
    # Copy pom trước để Docker cache được lớp tải thư viện khi chỉ sửa code
    COPY pom.xml ./
    RUN mvn -B -q dependency:go-offline
    
    COPY src ./src
    # Test chạy ở CI (./mvnw verify), khi build image bỏ qua cho nhanh
    RUN mvn -B -q -DskipTests package \
        && cp target/deployra-*.jar /workspace/app.jar
    
    # ---------- Stage 2: chạy bằng JRE 21 ----------
    FROM eclipse-temurin:21-jre AS runtime
    WORKDIR /app
    
    # curl dùng cho healthcheck của compose
    RUN apt-get update \
        && apt-get install -y --no-install-recommends curl \
        && rm -rf /var/lib/apt/lists/* \
        && useradd --system --uid 10001 --create-home deployra
    
    COPY --from=build /workspace/app.jar /app/app.jar
    USER deployra
    
    EXPOSE 8080
    ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
    ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]