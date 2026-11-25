# ĐÓNG GÓI HỆ THỐNG DEPLOYRA BẰNG DOCKERFILE & DOCKER COMPOSE

## 1. Mục đích
- Đóng gói toàn bộ hệ thống Deployra (Spring Boot Backend) thành Docker Container.
- Chạy Deployra cùng Docker Network (`deployra`) với **PostgreSQL**, **Traefik** và **các Container ứng dụng của người dùng**.
- **Khắc phục triệt để lỗi Healthcheck timeout (60s)**: Khi chạy trực tiếp trên máy chủ Windows host, hệ điều hành không route được trực tiếp vào IP nội bộ của Docker container (`http://172.x.x.x:port/`). Khi Deployra nằm trong Docker network `deployra`, nó có thể HTTP probe trực tiếp tới IP nội bộ của container người dùng ngay lập tức.

---

## 2. Các tệp đã tạo / cập nhật

### 2.1. Dockerfile (`c:\Deployra-System\deployra\Dockerfile`)
- **Stage 1 (Builder)**: Sử dụng `maven:3.9-eclipse-temurin-21-alpine` để build file jar tối ưu.
- **Stage 2 (Runtime)**: Sử dụng `eclipse-temurin:21-jre-alpine` siêu nhẹ.
- **Dependencies cần thiết**: Cài sẵn `docker-cli`, `git`, `curl` để Deployra có thể điều khiển Docker daemon, clone repo Git và thực hiện health check.

```dockerfile
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

# Cài đặt docker-cli, docker-cli-buildx, git và curl cho các tác vụ runtime
RUN apk add --no-cache docker-cli docker-cli-buildx git curl

COPY --from=builder /app/target/*.jar app.jar

ENV DOCKER_BUILDKIT=1
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 2.2. Tệp `.dockerignore` (`c:\Deployra-System\deployra\.dockerignore`)
Loại bỏ thư mục `target/`, `.git/`, `data/`, `test-projects/` khỏi Docker context giúp quá trình build image diễn ra nhanh chóng.

### 2.3. Cập nhật `docker-compose.yml` (`c:\Deployra-System\deployra\docker-compose.yml`)
Bổ sung service `deployra`:
- Mount `/var/run/docker.sock` để Deployra ra lệnh cho Docker daemon tạo container người dùng.
- Volume mount `./data:/app/data` ánh xạ trực tiếp thư mục `data` từ máy host vào container, đảm bảo các file zip đã upload trước đó (và sau này) không bị mất hay lỗi `NoSuchFileException`.
- Kết nối tới `postgres:5432` nội bộ qua biến `DB_URL: jdbc:postgresql://postgres:5432/deployra`.
- Gắn vào network `deployra` cùng với Traefik và Postgres.

---

## 3. Các cải tiến và sửa lỗi quan trọng trong quá trình triển khai

### 3.1. Sửa lỗi cập nhật Port Service (`ProjectBuildPreparer.java`)
- **Nguyên nhân**: Hàm `updateServicePort` trước đó có điều kiện `if (service.getPort() == null || service.getPort() == 8000)`. Khi một service đã từng mang port `8080` (hoặc tạo với port khác), khi deploy Frontend SPA (Nginx port 80), port trong DB không được cập nhật về 80. Kết quả là healthcheck probe gọi vào port cũ `8080` trong khi Nginx lắng nghe port `80`, dẫn đến timeout.
- **Khắc phục**: Đổi điều kiện thành `if (service.getPort() == null || service.getPort() != port)` để luôn đồng bộ đúng port mà Dockerfile sinh ra.

### 3.2. Cơ chế Healthcheck đa tầng (`DockerContainerRuntime.java`)
- **Tầng 1**: Thử HTTP GET trực tiếp tới `http://<container-ip>:<port><path>`.
- **Tầng 2 (Fallback)**: Thực thi `docker exec <containerId> curl -s -f http://127.0.0.1:<port><path>` trực tiếp trong container, bỏ qua độ trễ định tuyến giữa các container.
- **Tầng 3 (Fallback)**: Đọc trạng thái `{{.State.Health.Status}}` do Docker daemon theo dõi khi image có chỉ thị `HEALTHCHECK`.
- **Ghi log chi tiết**: Ghi log rõ ràng port, IP, path khi thành công hoặc timeout để dễ dàng giám sát.

---

## 4. Cách khởi chạy hệ thống

Mở terminal tại thư mục `c:\Deployra-System\deployra`:

```bash
# Build image và khởi chạy toàn bộ stack (Postgres + Traefik + Deployra App)
docker compose up -d --build
```

### Kiểm tra log hệ thống:
```bash
docker compose logs -f deployra
```

Hệ thống sẵn sàng tại: `http://localhost:8080`
Mọi service deploy bởi người dùng sẽ được tạo, probe healthcheck và định tuyến mượt mà.
