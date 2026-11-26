# Phân biệt Frontend vs Backend & Tự động sinh Dockerfile (Build Preparer)

Tài liệu này ghi lại cơ chế **nhận diện loại dự án tự động** và **bộ sinh Dockerfile thông minh** cho cả **Backend** và **Frontend** trong Deployra thông qua [`ProjectBuildPreparer`](file:///c:/Deployra-System/deployra/src/main/java/com/paas/deployra/deploy/ProjectBuildPreparer.java).

---

## 1. Phân biệt Frontend và Backend như thế nào?

Hệ thống phân tích cây thư mục và file cấu hình ở gốc mã nguồn theo thứ tự ưu tiên:

```
                          [MÃ NGUỒN ĐƯỢC TẢI LÊN]
                                     │
           ┌─────────────────────────┼─────────────────────────┐
           ▼                         ▼                         ▼
      Có pom.xml /              Có requirements.txt /       Có package.json
      build.gradle                  manage.py                  (JavaScript)
           │                         │                         │
     [JAVA BACKEND]           [PYTHON BACKEND]                 ▼
 (Maven/Gradle + Spring)     (FastAPI/Flask/Django)    Đọc nội dung package.json
                                                               │
                           ┌───────────────────────────────────┴───────────────────────────────────┐
                           ▼                                                                       ▼
             Có thư viện UI / Tooling:                                                Có framework Web Server:
             - "react", "react-dom", "vue"                                            - "express", "fastify", "koa"
             - "@angular/core", "svelte", "astro"                                     - "@nestjs/core", "socket.io"
             - "vite", "react-scripts"                                                - "mongoose", "prisma"
                           │                                                                       │
                    [FRONTEND SPA]                                                          [NODE.JS BACKEND]
```

### Bảng tóm tắt dấu hiệu nhận biết:

| Loại dự án | Dấu hiệu nhận biết chính | Cổng tiêu chuẩn |
| :--- | :--- | :--- |
| **Frontend SPA** | `package.json` chứa `react`, `vue`, `vite`, `svelte`, `astro` | Port `80` (Nginx) |
| **Node.js Backend** | `package.json` chứa `express`, `fastify`, `@nestjs`, `koa` hoặc có script `start` | Port `8000` |
| **Java Maven** | Có file `pom.xml` | Port `8080` (Spring Boot) |
| **Java Gradle** | Có file `build.gradle` hoặc `build.gradle.kts` | Port `8080` (Spring Boot) |
| **Python Backend** | Có file `requirements.txt` hoặc `manage.py` | Port `8000` (FastAPI/Django) |
| **Go Backend** | Có file `go.mod` | Port `8080` |

---

## 2. Cơ chế hoạt động của `ProjectBuildPreparer`

Trước khi lệnh `docker build` thực hiện, `ProjectBuildPreparer` sẽ:

1. **Chuẩn bị biến môi trường `.env`:**
   - Đọc toàn bộ biến môi trường đã giải mã từ `EnvVariableService` và ghi vào `.env` tại thư mục nguồn.
   - Nhờ đó, cả Frontend (`VITE_*`, `REACT_APP_*`) lẫn Backend đều có biến cấu hình ngay lúc build.

2. **Kiểm tra `Dockerfile` sẵn có:**
   - Nếu người dùng đã tự viết `Dockerfile`: Hệ thống giữ nguyên, ưu tiên dùng file của người dùng.
   - Nếu **chưa có `Dockerfile`**: Tự động kích hoạt bộ nhận diện và sinh `Dockerfile` tối ưu tương ứng.

3. **Tự động điều chỉnh Port:**
   - Tự động gán cổng service tương ứng với framework (ví dụ: Frontend Nginx $\rightarrow$ 80, Java Spring Boot $\rightarrow$ 8080).

---

## 3. Các mẫu Dockerfile được sinh tự động

### 3.1. Frontend SPA (React / Vue / Vite) $\rightarrow$ Nginx (Port 80)
Dùng Multi-stage build để chỉ giữ lại Nginx siêu nhẹ (~15MB RAM):
```dockerfile
# --- Stage 1: Build source ---
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# --- Stage 2: Serve bằng Nginx ---
FROM nginx:alpine
COPY deployra-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```
*(Kèm file `deployra-nginx.conf` có `try_files $uri $uri/ /index.html;` chống lỗi 404 khi F5)*.

---

### 3.2. Backend Java (Maven / Spring Boot) (Port 8080)
Dùng Multi-stage build để vứt bỏ bộ Maven sau khi compile, chỉ chạy bằng JRE 21:
```dockerfile
# --- Stage 1: Build Spring Boot JAR ---
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml ./
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# --- Stage 2: Runtime JRE 21 siêu nhẹ ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

---

### 3.3. Backend Node.js (Express / Fastify / NestJS) (Port 8000)
```dockerfile
FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN if npm run | grep -q " build"; then npm run build; fi
ENV PORT=8000
EXPOSE 8000
CMD ["npm", "start"]
```

---

### 3.4. Backend Python (FastAPI / Flask / Django) (Port 8000)
```dockerfile
FROM python:3.11-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PORT=8000
COPY requirements*.txt ./
RUN if [ -f requirements.txt ]; then pip install --no-cache-dir -r requirements.txt; fi
COPY . .
EXPOSE 8000
CMD ["sh", "-c", "if [ -f manage.py ]; then python manage.py runserver 0.0.0.0:8000; elif [ -f main.py ]; then python main.py; else python app.py; fi"]
```

---

### 3.5. Backend Go (Port 8080)
```dockerfile
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o main .

FROM alpine:latest
WORKDIR /app
COPY --from=builder /app/main .
ENV PORT=8080
EXPOSE 8080
CMD ["./main"]
```

---

### 3.6. Website tĩnh thuần HTML (chỉ có index.html / css / js, không có package.json) (Port 80)
Khi người dùng chỉ tải lên duy nhất 1 file `index.html` hoặc một thư mục web tĩnh HTML thuần:
- **Không cần Node.js, không cần `npm install`, không cần build.**
- Phục vụ trực tiếp bằng Nginx siêu nhẹ (~10MB RAM), thời gian build chỉ mất **1 giây**:

```dockerfile
FROM nginx:alpine
COPY . /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

> **Tính năng tự động bọc Zip cho file HTML đơn lẻ:**  
> Tại API `POST /api/services/{id}/uploads`, nếu người dùng tải lên trực tiếp 1 file `.html` (thay vì file `.zip`), `SourceUploadService` sẽ **tự động bọc file HTML đó thành một file ZIP hợp lệ** chứa `index.html` để các khâu lưu trữ, giải nén và build phía sau hoạt động hoàn toàn tự động và đồng bộ.
