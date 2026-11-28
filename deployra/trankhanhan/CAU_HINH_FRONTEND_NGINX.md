# Cấu hình Triển khai Frontend (Cách 1: Multi-stage Dockerfile + Nginx)

Tài liệu này ghi lại toàn bộ cơ chế, mã nguồn và kiến trúc xử lý ứng dụng **Frontend (React, Vue, Vite, Next.js static, Angular...)** trên nền tảng Deployra theo **Cách 1: Container hóa với Nginx**.

---

## 1. Mục tiêu và Nguyên lý hoạt động

- **Đồng bộ với pipeline Deployra hiện tại:** Sử dụng chung luồng `DeploymentService` $\rightarrow$ `DockerContainerRuntime` $\rightarrow$ `Traefik` mà không cần thay đổi kiến trúc hạ tầng.
- **Tiết kiệm tài nguyên:** Thay vì chạy Node.js tốn 300 – 500 MB RAM chỉ để phục vụ file tĩnh, ứng dụng chạy trên Nginx siêu nhẹ chỉ tốn **~10 – 15 MB RAM** (chạy thoải mái trên gói Free 256 MB).
- **Trải nghiệm người dùng không cần viết Dockerfile:** Nếu người dùng tải lên project Frontend có `package.json` mà không có `Dockerfile`, hệ thống sẽ **tự động sinh Dockerfile và Nginx config**.

---

## 2. Các thành phần mới được bổ sung

### 2.1. Class `FrontendBuildPreparer.java`
Nằm tại: [`src/main/java/com/paas/deployra/deploy/FrontendBuildPreparer.java`](file:///c:/Deployra-System/deployra/src/main/java/com/paas/deployra/deploy/FrontendBuildPreparer.java)

Thực hiện 3 nhiệm vụ tự động trước khi gọi `docker build`:

1. **Chuẩn bị biến môi trường lúc Build:**
   - Frontend (như Vite, Create-React-App) yêu cầu các biến `VITE_*`, `REACT_APP_*` phải có mặt ngay lúc chạy lệnh `npm run build` để nhúng vào file JS.
   - `FrontendBuildPreparer` lấy danh sách biến môi trường đã giải mã từ `EnvVariableService` và ghi vào file `.env` ở thư mục nguồn trước khi build.

2. **Tự động nhận diện và sinh Multi-stage Dockerfile:**
   - Nếu thư mục nguồn đã có `Dockerfile` của người dùng: Giữ nguyên, không can thiệp.
   - Nếu **chưa có `Dockerfile`** nhưng có `package.json`:
     - Tự phát hiện Package Manager: `pnpm` (nếu có `pnpm-lock.yaml`), `yarn` (nếu có `yarn.lock`), hoặc `npm`.
     - Tự phát hiện thư mục đầu ra: `dist` (Vite, Vue, TS), `build` (Create-React-App), `out` (Next.js static export).
     - Sinh `Dockerfile` 2 giai đoạn:
       - **Stage 1 (Builder):** `node:20-alpine` chạy cài dependencies và biên dịch `npm run build`.
       - **Stage 2 (Runner):** `nginx:alpine` copy thư mục kết quả (`dist`) vào `/usr/share/nginx/html`.

3. **Chống lỗi 404 khi F5 (Client-side Routing):**
   - Tự động sinh file `deployra-nginx.conf` với rule:
     ```nginx
     location / {
         try_files $uri $uri/ /index.html;
     }
     ```
   - Nhờ đó, người dùng dùng React Router / Vue Router truy cập các đường dẫn như `/dashboard`, `/login` khi bấm F5 hoặc mở link trực tiếp sẽ không bị lỗi 404.

4. **Tự động chuyển Port về 80:**
   - Nếu service đang để port mặc định `8000`, hệ thống tự động cập nhật `service.port = 80` để khớp với cổng mặc định của Nginx, giúp Traefik định tuyến chính xác.

---

### 2.2. Tích hợp vào `DeploymentService.java`

Trong hàm `pipeline()` của [`DeploymentService.java`](file:///c:/Deployra-System/deployra/src/main/java/com/paas/deployra/deploy/DeploymentService.java), ngay sau khi giải nén ZIP hoặc clone Git:

```java
// Chuẩn bị biến .env cho build và tự sinh Dockerfile Nginx nếu là dự án Frontend
buildPreparer.prepare(svc, sourceDir, logSink);

if (!Files.isRegularFile(sourceDir.resolve(DOCKERFILE))) {
    fail(d, "Thiếu Dockerfile ở gốc mã nguồn");
    return;
}
```

Nhờ đó, bước kiểm tra `Dockerfile` luôn thành công đối với cả project Frontend thông thường mà người dùng không cần phải tự cấu hình thêm gì.

---

### 2.3. Kiểm thử tự động (`FrontendBuildPreparerTest.java`)
Nằm tại: [`src/test/java/com/paas/deployra/deploy/FrontendBuildPreparerTest.java`](file:///c:/Deployra-System/deployra/src/test/java/com/paas/deployra/deploy/FrontendBuildPreparerTest.java)

Bao gồm 3 test case:
- `preparesEnvFileFromDecryptedVariables`: Kiểm tra ghi đúng biến môi trường vào `.env`.
- `autoGeneratesNginxDockerfileWhenPackageJsonExistsWithoutDockerfile`: Kiểm tra tự sinh Dockerfile chuẩn multi-stage, file `deployra-nginx.conf` có fallback SPA routing, và cập nhật port sang 80.
- `doesNotOverwriteExistingDockerfile`: Đảm bảo không ghi đè nếu người dùng đã có Dockerfile riêng.

---

## 3. Mẫu Dockerfile Nginx được sinh tự động

Khi người dùng deploy một project React + Vite thông thường, file Dockerfile được sinh tự động sẽ có cấu trúc như sau:

```dockerfile
# --- Stage 1: Build source ---
FROM node:20-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .
RUN npm run build

# --- Stage 2: Serve bằng Nginx siêu nhẹ ---
FROM nginx:alpine
COPY deployra-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

---

## 4. Định hướng nâng cấp lên Cách 2 trong tương lai

Khi hệ thống phát triển lớn hơn và muốn tối ưu RAM về mức **0 MB** cho Frontend:
1. Thêm loại service `STATIC_SITE` vào enum `AppService.Type`.
2. Tạo một worker build tạm thời chạy `npm run build` để xuất thư mục `dist/` ra ổ cứng máy chủ hoặc S3.
3. Sử dụng 1 Nginx/Caddy server dùng chung cho toàn hệ thống để map subdomain `slug.domain.com` vào thư mục tĩnh tương ứng.
