package com.paas.deployra.deploy;

import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.EnvVariableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tự động phát hiện loại dự án và chuẩn bị mã nguồn trước khi Docker build.
 *
 * Với Dockerfile do người dùng cung cấp, class này giữ nguyên Dockerfile đó.
 * Nếu chưa có Dockerfile, class tự sinh Dockerfile phù hợp với loại dự án.
 */
@Component
public class ProjectBuildPreparer {

    private static final Logger log = LoggerFactory.getLogger(ProjectBuildPreparer.class);

    public static final String DOCKERFILE = "Dockerfile";
    public static final String PACKAGE_JSON = "package.json";
    public static final String POM_XML = "pom.xml";
    public static final String BUILD_GRADLE = "build.gradle";
    public static final String BUILD_GRADLE_KTS = "build.gradle.kts";
    public static final String REQUIREMENTS_TXT = "requirements.txt";
    public static final String GO_MOD = "go.mod";
    public static final String NGINX_CONF = "deployra-nginx.conf";

    private static final int STATIC_FRONTEND_PORT = 8080;

    public enum ProjectType {
        STATIC_HTML,
        FRONTEND_SPA,
        NODE_BACKEND,
        JAVA_MAVEN,
        JAVA_GRADLE,
        PYTHON,
        GO,
        UNKNOWN
    }

    private static final Set<String> FRONTEND_INDICATORS = Set.of(
            "\"react\"", "\"react-dom\"", "\"vue\"", "\"@angular/core\"",
            "\"svelte\"", "\"vite\"", "\"astro\"", "\"react-scripts\"");

    private static final Set<String> BACKEND_INDICATORS = Set.of(
            "\"express\"", "\"fastify\"", "\"koa\"", "\"@nestjs/core\"",
            "\"hapi\"", "\"socket.io\"", "\"mongoose\"", "\"prisma\"", "\"typeorm\"");

    private final EnvVariableService envVars;
    private final AppServiceRepository services;

    public ProjectBuildPreparer(EnvVariableService envVars, AppServiceRepository services) {
        this.envVars = envVars;
        this.services = services;
    }

    /**
     * Chuẩn bị mã nguồn trước khi gọi docker build:
     * 1. Ghi các biến môi trường đã giải mã vào .env để phục vụ build.
     * 2. Nếu đã có Dockerfile thì tôn trọng Dockerfile của người dùng.
     * 3. Nếu chưa có Dockerfile thì nhận diện loại dự án và tự sinh Dockerfile.
     */
    public ProjectType prepare(AppService service, Path sourceDir, Consumer<String> logSink) {
        if (service == null || sourceDir == null || !Files.isDirectory(sourceDir)) {
            return ProjectType.UNKNOWN;
        }

        injectBuildEnv(service, sourceDir, logSink);

        // Người dùng đã cung cấp Dockerfile: không ghi đè cấu hình của họ.
        if (Files.isRegularFile(sourceDir.resolve(DOCKERFILE))) {
            if (logSink != null) {
                logSink.accept("Tìm thấy Dockerfile có sẵn — sử dụng Dockerfile của người dùng");
            }
            return ProjectType.UNKNOWN;
        }

        ProjectType type = detectProjectType(sourceDir);

        switch (type) {
            case STATIC_HTML -> generateStaticHtmlDockerfile(service, sourceDir, logSink);
            case FRONTEND_SPA -> generateFrontendSpaDockerfile(service, sourceDir, logSink);
            case NODE_BACKEND -> generateNodeBackendDockerfile(service, sourceDir, logSink);
            case JAVA_MAVEN -> generateJavaMavenDockerfile(service, sourceDir, logSink);
            case JAVA_GRADLE -> generateJavaGradleDockerfile(service, sourceDir, logSink);
            case PYTHON -> generatePythonDockerfile(service, sourceDir, logSink);
            case GO -> generateGoDockerfile(service, sourceDir, logSink);
            case UNKNOWN -> {
                log.info("Không nhận diện được loại dự án để tự sinh Dockerfile cho service {}", service.getId());
                if (logSink != null) {
                    logSink.accept("Không nhận diện được loại dự án — vui lòng cung cấp Dockerfile");
                }
            }
        }

        return type;
    }

    /**
     * Nhận diện loại dự án dựa trên các file cấu hình ở thư mục gốc sourceDir.
     */
    public ProjectType detectProjectType(Path sourceDir) {
        if (Files.isRegularFile(sourceDir.resolve(POM_XML))) {
            return ProjectType.JAVA_MAVEN;
        }

        if (Files.isRegularFile(sourceDir.resolve(BUILD_GRADLE))
                || Files.isRegularFile(sourceDir.resolve(BUILD_GRADLE_KTS))) {
            return ProjectType.JAVA_GRADLE;
        }

        if (Files.isRegularFile(sourceDir.resolve(REQUIREMENTS_TXT))
                || Files.isRegularFile(sourceDir.resolve("manage.py"))) {
            return ProjectType.PYTHON;
        }

        if (Files.isRegularFile(sourceDir.resolve(GO_MOD))) {
            return ProjectType.GO;
        }

        if (Files.isRegularFile(sourceDir.resolve(PACKAGE_JSON))) {
            return detectNodeSubtype(sourceDir.resolve(PACKAGE_JSON));
        }

        if (Files.isRegularFile(sourceDir.resolve("index.html"))
                || Files.isRegularFile(sourceDir.resolve("index.htm"))) {
            return ProjectType.STATIC_HTML;
        }

        return ProjectType.UNKNOWN;
    }

    /**
     * Phân biệt Frontend SPA và Backend Node.js dựa trên dependencies trong
     * package.json.
     */
    private ProjectType detectNodeSubtype(Path packageJsonPath) {
        try {
            String content = Files.readString(packageJsonPath, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);

            boolean hasFrontend = FRONTEND_INDICATORS.stream().anyMatch(content::contains);
            boolean hasBackend = BACKEND_INDICATORS.stream().anyMatch(content::contains);

            if (hasFrontend && !hasBackend) {
                return ProjectType.FRONTEND_SPA;
            }

            if (hasBackend) {
                return ProjectType.NODE_BACKEND;
            }

            if (content.contains("\"build\"")
                    && (content.contains("vite") || content.contains("webpack"))) {
                return ProjectType.FRONTEND_SPA;
            }

            // Mặc định package.json không có dấu hiệu frontend/backend rõ ràng là Node
            // backend.
            return ProjectType.NODE_BACKEND;
        } catch (IOException e) {
            log.warn("Không thể đọc package.json: {}", e.getMessage());
            return ProjectType.NODE_BACKEND;
        }
    }

    // -------------------------------------------------------------------------
    // Dockerfile generators
    // -------------------------------------------------------------------------

    /**
     * Website HTML/CSS/JS thuần.
     *
     * nginx-unprivileged chạy bằng UID 101 và lắng nghe port 8080.
     * Không dùng nginx:alpine mặc định vì entrypoint của image đó có thể cần
     * quyền CHOWN, trong khi container Deployra đang drop toàn bộ Linux
     * capabilities.
     */
    private void generateStaticHtmlDockerfile(
            AppService service, Path sourceDir, Consumer<String> logSink) {
        try {
            String nginxConf = """
                    server {
                        listen 8080;
                        server_name _;

                        root /usr/share/nginx/html;
                        index index.html index.htm;

                        location = /healthz.html {
                            access_log off;
                            default_type text/plain;
                            return 200 "OK";
                        }

                        location ~ /\\. {
                            deny all;
                        }

                        location / {
                            try_files $uri $uri/ =404;
                        }
                    }
                    """;

            Files.writeString(sourceDir.resolve(NGINX_CONF), nginxConf, StandardCharsets.UTF_8);

            String dockerfile = """
                    FROM nginxinc/nginx-unprivileged:alpine

                    COPY --chown=101:101 %s /etc/nginx/conf.d/default.conf
                    COPY --chown=101:101 . /usr/share/nginx/html

                    RUN printf "OK" > /usr/share/nginx/html/healthz.html

                    EXPOSE 8080

                    HEALTHCHECK --interval=5s --timeout=5s --start-period=10s --retries=3 \\
                      CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz.html || exit 1

                    CMD ["nginx", "-g", "daemon off;"]
                    """.formatted(NGINX_CONF);

            Files.writeString(sourceDir.resolve(DOCKERFILE), dockerfile, StandardCharsets.UTF_8);
            updateServicePort(service, STATIC_FRONTEND_PORT);

            if (logSink != null) {
                logSink.accept(
                        "Đã phát hiện website HTML tĩnh — tạo Dockerfile nginx-unprivileged (port 8080)");
            }
        } catch (IOException e) {
            log.error("Không thể sinh Dockerfile Static HTML cho service {}: {}",
                    service.getId(), e.getMessage(), e);
        }
    }

    /**
     * Frontend SPA: Node.js build ở stage 1, Nginx non-root phục vụ file build ở
     * stage 2.
     * Hỗ trợ npm, pnpm và yarn; thư mục output phổ biến: dist, build, out.
     */
    private void generateFrontendSpaDockerfile(
            AppService service, Path sourceDir, Consumer<String> logSink) {
        try {
            String packageJsonContent = Files.readString(
                    sourceDir.resolve(PACKAGE_JSON), StandardCharsets.UTF_8);

            String outputDir = "dist";
            if (packageJsonContent.contains("\"react-scripts\"")) {
                outputDir = "build";
            } else if (packageJsonContent.contains("\"next\"")
                    && packageJsonContent.contains("\"export\"")) {
                outputDir = "out";
            }

            String installAndBuildCommand;
            if (Files.exists(sourceDir.resolve("pnpm-lock.yaml"))) {
                installAndBuildCommand = """
                        RUN corepack enable \\
                            && corepack prepare pnpm@latest --activate \\
                            && pnpm install --frozen-lockfile \\
                            && pnpm run build
                        """;
            } else if (Files.exists(sourceDir.resolve("yarn.lock"))) {
                installAndBuildCommand = """
                        RUN corepack enable \\
                            && yarn install --frozen-lockfile \\
                            && yarn build
                        """;
            } else {
                installAndBuildCommand = """
                        RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi \\
                            && npm run build
                        """;
            }

            String nginxConf = """
                    server {
                        listen 8080;
                        server_name _;

                        root /usr/share/nginx/html;
                        index index.html;

                        location = /healthz.html {
                            access_log off;
                            default_type text/plain;
                            return 200 "OK";
                        }

                        location ~ /\\. {
                            deny all;
                        }

                        location / {
                            try_files $uri $uri/ /index.html;
                        }
                    }
                    """;

            Files.writeString(sourceDir.resolve(NGINX_CONF), nginxConf, StandardCharsets.UTF_8);

            /*
             * COPY toàn bộ source vào builder trước khi cài dependencies để lockfile
             * (package-lock/yarn.lock/pnpm-lock) chắc chắn có mặt khi chạy install.
             * Chỉ thư mục output được copy sang runtime stage, không đưa node_modules
             * hay source code sang image Nginx cuối cùng.
             */
            String dockerfile = """
                    # --- Stage 1: Build frontend ---
                    FROM node:20-alpine AS builder
                    WORKDIR /app

                    COPY . .

                    %s

                    # Tạo endpoint health-check ngay trong build stage.
                    RUN printf "OK" > /app/%s/healthz.html

                    # --- Stage 2: Serve static files bằng Nginx non-root ---
                    FROM nginxinc/nginx-unprivileged:alpine

                    COPY --chown=101:101 %s /etc/nginx/conf.d/default.conf
                    COPY --chown=101:101 --from=builder /app/%s/ /usr/share/nginx/html/

                    EXPOSE 8080

                    HEALTHCHECK --interval=5s --timeout=5s --start-period=10s --retries=3 \\
                      CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz.html || exit 1

                    CMD ["nginx", "-g", "daemon off;"]
                    """.formatted(installAndBuildCommand, outputDir, NGINX_CONF, outputDir);

            Files.writeString(sourceDir.resolve(DOCKERFILE), dockerfile, StandardCharsets.UTF_8);
            updateServicePort(service, STATIC_FRONTEND_PORT);

            if (logSink != null) {
                logSink.accept(
                        "Đã phát hiện dự án FRONTEND (React/Vue/Vite) — build bằng Node.js, phục vụ bằng Nginx non-root (port 8080)");
            }
        } catch (IOException e) {
            log.error("Không thể sinh Dockerfile Frontend cho service {}: {}",
                    service.getId(), e.getMessage(), e);
        }
    }

    /**
     * Backend Node.js (Express, Fastify, NestJS...).
     */
    private void generateNodeBackendDockerfile(
            AppService service, Path sourceDir, Consumer<String> logSink) {
        try {
            int port = service.getPort() != null ? service.getPort() : 8000;
            updateServicePort(service, port);

            String dockerfile = """
                    FROM node:20-alpine
                    WORKDIR /app

                    COPY package*.json ./
                    RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

                    COPY . .

                    # Build nếu dự án có script build (ví dụ TypeScript/NestJS).
                    RUN if npm run | grep -q " build"; then npm run build; fi

                    ENV NODE_ENV=production
                    ENV PORT=%d
                    EXPOSE %d

                    CMD ["npm", "start"]
                    """.formatted(port, port);

            Files.writeString(sourceDir.resolve(DOCKERFILE), dockerfile, StandardCharsets.UTF_8);

            if (logSink != null) {
                logSink.accept(
                        "Đã phát hiện dự án BACKEND NODE.JS — tạo Dockerfile Node.js (port " + port + ")");
            }
        } catch (IOException e) {
            log.error("Không thể sinh Dockerfile Node Backend cho service {}: {}",
                    service.getId(), e.getMessage(), e);
        }
    }

    /**
     * Backend Java Maven / Spring Boot.
     */
    private void generateJavaMavenDockerfile(
            AppService service, Path sourceDir, Consumer<String> logSink) {
        try {
            updateServicePort(service, 8080);

            String dockerfile = """
                    # --- Stage 1: Build Spring Boot JAR ---
                    FROM maven:3.9-eclipse-temurin-21-alpine AS builder
                    WORKDIR /app

                    COPY pom.xml ./
                    RUN mvn dependency:go-offline -B

                    COPY src ./src
                    RUN mvn package -DskipTests -B

                    # --- Stage 2: Runtime JRE ---
                    FROM eclipse-temurin:21-jre-alpine
                    WORKDIR /app

                    COPY --from=builder /app/target/*.jar app.jar

                    ENV PORT=8080
                    EXPOSE 8080
                    CMD ["java", "-jar", "app.jar"]
                    """;

            Files.writeString(sourceDir.resolve(DOCKERFILE), dockerfile, StandardCharsets.UTF_8);

            if (logSink != null) {
                logSink.accept(
                        "Đã phát hiện dự án BACKEND JAVA (Maven/Spring Boot) — tạo Dockerfile Java 21 (port 8080)");
            }
        } catch (IOException e) {
            log.error("Không thể sinh Dockerfile Java Maven cho service {}: {}",
                    service.getId(), e.getMessage(), e);
        }
    }

    /**
     * Backend Java Gradle / Spring Boot.
     */
    private void generateJavaGradleDockerfile(
            AppService service, Path sourceDir, Consumer<String> logSink) {
        try {
            updateServicePort(service, 8080);

            String dockerfile = """
                    # --- Stage 1: Build JAR bằng Gradle ---
                    FROM gradle:8-jdk21-alpine AS builder
                    WORKDIR /app

                    COPY . .
                    RUN gradle bootJar --no-daemon -x test

                    # --- Stage 2: Runtime JRE ---
                    FROM eclipse-temurin:21-jre-alpine
                    WORKDIR /app

                    COPY --from=builder /app/build/libs/*.jar app.jar

                    ENV PORT=8080
                    EXPOSE 8080
                    CMD ["java", "-jar", "app.jar"]
                    """;

            Files.writeString(sourceDir.resolve(DOCKERFILE), dockerfile, StandardCharsets.UTF_8);

            if (logSink != null) {
                logSink.accept(
                        "Đã phát hiện dự án BACKEND JAVA (Gradle/Spring Boot) — tạo Dockerfile Java 21 (port 8080)");
            }
        } catch (IOException e) {
            log.error("Không thể sinh Dockerfile Java Gradle cho service {}: {}",
                    service.getId(), e.getMessage(), e);
        }
    }

    /**
     * Backend Python (FastAPI, Flask, Django).
     *
     * Lưu ý: ứng dụng Python phải tự bind 0.0.0.0 và đọc biến PORT.
     */
    private void generatePythonDockerfile(
            AppService service, Path sourceDir, Consumer<String> logSink) {
        try {
            int port = service.getPort() != null ? service.getPort() : 8000;
            updateServicePort(service, port);

            String dockerfile = """
                    FROM python:3.11-slim
                    WORKDIR /app

                    ENV PYTHONDONTWRITEBYTECODE=1
                    ENV PYTHONUNBUFFERED=1
                    ENV PORT=%d

                    COPY requirements*.txt ./
                    RUN if [ -f requirements.txt ]; then pip install --no-cache-dir -r requirements.txt; fi

                    COPY . .

                    EXPOSE %d
                    CMD ["sh", "-c", "if [ -f manage.py ]; then python manage.py runserver 0.0.0.0:$PORT; elif [ -f main.py ]; then python main.py; else python app.py; fi"]
                    """
                    .formatted(port, port);

            Files.writeString(sourceDir.resolve(DOCKERFILE), dockerfile, StandardCharsets.UTF_8);

            if (logSink != null) {
                logSink.accept(
                        "Đã phát hiện dự án BACKEND PYTHON — tạo Dockerfile Python 3.11 (port " + port + ")");
            }
        } catch (IOException e) {
            log.error("Không thể sinh Dockerfile Python cho service {}: {}",
                    service.getId(), e.getMessage(), e);
        }
    }

    /**
     * Backend Go.
     */
    private void generateGoDockerfile(
            AppService service, Path sourceDir, Consumer<String> logSink) {
        try {
            int port = service.getPort() != null ? service.getPort() : 8080;
            updateServicePort(service, port);

            String dockerfile = """
                    # --- Stage 1: Build Go binary ---
                    FROM golang:1.22-alpine AS builder
                    WORKDIR /app

                    COPY go.mod go.sum* ./
                    RUN go mod download

                    COPY . .
                    RUN CGO_ENABLED=0 GOOS=linux go build -o main .

                    # --- Stage 2: Runtime ---
                    FROM alpine:latest
                    WORKDIR /app

                    COPY --from=builder /app/main ./

                    ENV PORT=%d
                    EXPOSE %d
                    CMD ["./main"]
                    """.formatted(port, port);

            Files.writeString(sourceDir.resolve(DOCKERFILE), dockerfile, StandardCharsets.UTF_8);

            if (logSink != null) {
                logSink.accept(
                        "Đã phát hiện dự án BACKEND GO — tạo Dockerfile Go (port " + port + ")");
            }
        } catch (IOException e) {
            log.error("Không thể sinh Dockerfile Go cho service {}: {}",
                    service.getId(), e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Chuẩn bị .env từ các biến môi trường đã giải mã.
     *
     * File .env chỉ nằm trong build context; với frontend SPA, runtime image chỉ
     * nhận thư mục output đã build chứ không nhận toàn bộ source/builder stage.
     */
    private void injectBuildEnv(
            AppService service, Path sourceDir, Consumer<String> logSink) {
        try {
            Map<String, String> vars = envVars.decryptedFor(service.getId());
            if (vars == null || vars.isEmpty()) {
                return;
            }

            Path envFile = sourceDir.resolve(".env");
            StringBuilder content = new StringBuilder();

            if (Files.exists(envFile)) {
                String existing = Files.readString(envFile, StandardCharsets.UTF_8);
                content.append(existing);
                if (!existing.isEmpty() && !existing.endsWith("\n")) {
                    content.append('\n');
                }
            }

            for (Map.Entry<String, String> entry : vars.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    content.append(key).append('=').append(value).append('\n');
                }
            }

            Files.writeString(envFile, content.toString(), StandardCharsets.UTF_8);

            if (logSink != null) {
                logSink.accept("Đã chuẩn bị biến môi trường vào .env cho bước build");
            }
        } catch (Exception e) {
            log.warn("Không thể chuẩn bị file .env cho service {}: {}",
                    service.getId(), e.getMessage());
        }
    }

    private void updateServicePort(AppService service, int port) {
        if (service.getPort() == null || service.getPort() != port) {
            service.setPort(port);
            services.save(service);
        }
    }
}
