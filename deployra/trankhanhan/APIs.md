# Tài Liệu Tổng Hợp Toàn Bộ REST API — Deployra

Tài liệu này tổng hợp và giải thích chi tiết **toàn bộ các REST API** hiện có trong dự án `deployra`, dựa trên mã nguồn thực tế của các Controller và DTO.

---

## Quy ước chung

* **Base URL mặc định (local):** `http://localhost:8080`
* **Định dạng dữ liệu:** JSON (`application/json`), riêng API upload file dùng `multipart/form-data`.
* **Xử lý lỗi chuẩn (`ApiExceptionHandler`):**
  * `400 Bad Request` (`error: "invalid_request"`): Dữ liệu đầu vào không hợp lệ, thiếu tham số hoặc vi phạm ràng buộc nghiệp vụ.
  * `403 Forbidden` (`error: "quota_exceeded"`): Vượt quá hạn mức của gói hiện tại (trả kèm `key` hạn mức và `message`).
  * `404 Not Found` (`error: "not_found"`): Không tìm thấy tài nguyên (hoặc tài nguyên không thuộc quyền sở hữu).
  * `409 Conflict` (`error: "email_taken"`): Email đã được đăng ký.

---

## 1. Tài khoản, Hồ sơ & Hạn mức (Auth, Me, Quota)

### 1.1. Đăng ký tài khoản — `AuthController` (`/api/auth`)

* **`POST /api/auth/register`**
  * **Chức năng:** Tạo tài khoản người dùng mới (mặc định ở gói `Free`, mật khẩu mã hóa BCrypt).
  * **Request Body:**
    ```json
    {
      "email": "user@example.com",
      "password": "Password@123",
      "firstName": "An",
      "lastName": "Trần"
    }
    ```
    *(Validate: `email` hợp lệ, `password` từ 8–72 ký tự).*
  * **Response (`201 CREATED`):** `{"id": 1, "email": "user@example.com"}`
  * **Lỗi:** `409 Conflict` nếu email đã tồn tại.

### 1.2. Thông tin người dùng hiện tại — `MeController` (`/api/me`)

* **`GET /api/me`**
  * **Chức năng:** Trả về thông tin tài khoản đang đăng nhập, gói cước hiện tại và cờ quyền admin.
  * **Response (`200 OK`):**
    ```json
    {
      "id": 1,
      "email": "user@example.com",
      "firstName": "An",
      "lastName": "Trần",
      "planCode": "free",
      "planName": "Free",
      "admin": false
    }
    ```

### 1.3. Hạn mức & Mua Add-on — `QuotaController` (`/api/me`)

* **`GET /api/me/quota`**
  * **Chức năng:** Lấy báo cáo hạn mức sử dụng của người dùng hiện tại (số lượng đã dùng / giới hạn tối đa cho từng key: project, service, database, RAM, storage, bandwidth, build minutes) và danh sách add-on đang hiệu lực.
  * **Response (`200 OK`):** `QuotaDtos.Report` (`planCode`, `planName`, `lines`, `activeAddons`).
* **`POST /api/me/addons/{packageId}/purchase`**
  * **Chức năng:** Mua thêm gói add-on (thanh toán giả lập), cộng thêm hạn mức cho tài khoản.
  * **Response (`201 CREATED`):** `QuotaDtos.PurchaseResponse` (`paymentId`, `addonId`, `expiresAt`, `amount`, `currency`).

---

## 2. Bảng giá & Danh mục công khai — `CatalogController` (`/api`)

Các API công khai phục vụ trang bảng giá:

* **`GET /api/plans`**
  * **Chức năng:** Liệt kê các gói cước đang kích hoạt (`Free`, `Starter`, `Pro`...) kèm giá, chu kỳ thanh toán và bảng giới hạn (`limits`).
  * **Response (`200 OK`):** Danh sách `PlanView` (`id`, `code`, `name`, `price`, `currency`, `billingPeriodDays`, `limits`).
* **`GET /api/addons`**
  * **Chức năng:** Liệt kê các gói add-on đang bán (thêm RAM, thêm bandwidth...).
  * **Response (`200 OK`):** Danh sách `AddonView` (`id`, `name`, `entitlementKey`, `delta`, `validityType`, `validityDays`, `price`).

---

## 3. Quản lý Dự án (Projects) — `ProjectController` (`/api/projects`)

* **`GET /api/projects`**
  * **Query param:** `?userId={id}` (tùy chọn).
  * **Chức năng:** Liệt kê các project chưa bị xóa mềm, kèm số lượng service bên trong (`serviceCount`).
* **`GET /api/projects/{projectId}`**
  * **Chức năng:** Xem chi tiết một project kèm danh sách tóm tắt các service trực thuộc (`ProjectDetailResponse`).
* **`POST /api/projects`**
  * **Request Body:** `{"name": "My App", "userId": 1}` (`userId` tùy chọn).
  * **Chức năng:** Tạo project mới. Kiểm tra hạn mức `max_projects` qua `EntitlementService.requireCanCreateProject`.
  * **Response (`201 CREATED`):** `ProjectResponse` (`id`, `name`, `ownerId`, `createdAt`, `serviceCount`).
* **`PUT /api/projects/{projectId}`**
  * **Request Body:** `{"name": "New Name"}`
  * **Chức năng:** Cập nhật tên project.
* **`DELETE /api/projects/{projectId}`**
  * **Chức năng:** Xóa mềm project (`deletedAt = now()`) và toàn bộ service bên trong. Trả về `204 No Content`.

---

## 4. Quản lý Service & Vòng đời Container — `AppServiceController` & `ServiceController`

### 4.1. CRUD & Điều khiển Runtime — `AppServiceController`

* **`GET /api/projects/{projectId}/services`**
  * **Chức năng:** Liệt kê tất cả service (`APP` và `DATABASE`) trong một project.
* **`POST /api/projects/{projectId}/services`**
  * **Chức năng:** Tạo mới service trong project, tự sinh `slug` duy nhất cho subdomain Traefik (`http://{slug}.{baseDomain}`). Kiểm tra hạn mức qua `EntitlementService.requireCanCreateService`.
  * **Request Body:**
    ```json
    {
      "name": "todo-frontend",
      "type": "APP",
      "port": 80,
      "rootDir": ".",
      "dockerfilePath": "Dockerfile",
      "memoryMb": 256,
      "cpuMillicores": 500,
      "repoUrl": "https://github.com/user/repo",
      "branch": "main"
    }
    ```
  * **Response (`201 CREATED`):** `ServiceResponse` (gồm `id`, `slug`, `status`, `accessUrl`, `port`, `memoryMb`, `cpuMillicores`...).
* **`GET /api/services/{serviceId}`**
  * **Chức năng:** Xem thông tin chi tiết và cấu hình của 1 service.
* **`PUT /api/services/{serviceId}`**
  * **Chức năng:** Cập nhật cấu hình service (`name`, `port`, `branch`, `rootDir`, `dockerfilePath`, `memoryMb`, `cpuMillicores`, `healthcheckPath`).
* **`DELETE /api/services/{serviceId}`**
  * **Chức năng:** Xóa mềm service và gọi `ContainerRuntime.remove(service)` để dọn dẹp container Docker. Trả về `204 No Content`.
* **`POST /api/services/{serviceId}/start`** (`?userId={id}` tùy chọn)
  * **Chức năng:** Khởi động lại container đang dừng (kiểm tra pool RAM qua `requireCanStartService`). Hỗ trợ cả `APP` và `DATABASE`.
* **`POST /api/services/{serviceId}/stop`** (`?userId={id}` tùy chọn)
  * **Chức năng:** Dừng container (`STOPPED`) để giải phóng RAM trong pool.
* **`POST /api/services/{serviceId}/restart`** (`?userId={id}` tùy chọn)
  * **Chức năng:** Dừng rồi khởi động lại container.
* **`GET /api/services/{serviceId}/logs`** (`?tailLines=100`)
  * **Chức năng:** Đọc log runtime trực tiếp từ container Docker đang chạy.
* **`GET /api/services/{serviceId}/metrics`**
  * **Chức năng:** Lấy thông số tiêu thụ tài nguyên thời gian thực của container (`cpuPercent`, `memoryBytes`, `memoryMb`).

### 4.2. Tiện ích bổ sung cho Service — `ServiceController` (`/api/services`)

* **`GET /api/services/{id}/deployments/latest`**
  * **Chức năng:** Lấy nhanh thông tin deployment mới nhất của service (`DeploymentDtos.View`).
* **`POST /api/services/{id}/deploy`**
  * **Chức năng:** Kích hoạt nhanh một lần deploy mới từ cấu hình hiện tại của service (trả về `202 Accepted`).

---

## 5. Nguồn mã (Upload ZIP & Kiểm tra Git Repo)

### 5.1. Upload & Gắn Repo — `UploadController` (`/api/services/{serviceId}`)

* **`POST /api/services/{serviceId}/uploads`** (`multipart/form-data`)
  * **Params:** `file` (`.zip` hoặc `.html`/`.htm` — nếu là `.html` hệ thống tự gói thành zip có `index.html`), `userId` (tùy chọn).
  * **Chức năng:** Chỉ lưu file nguồn vào storage (`LocalSourceStorage`), kiểm tra dung lượng & định dạng ZIP, tự động import key biến môi trường từ `.env`/`.env.example` (không lấy value), **không** tự động deploy ngay.
  * **Response (`201 CREATED`):** `{"uploadId": 12, "sizeBytes": 2048, "sha256": "...", "expiresAt": "..."}`
* **`PUT /api/services/{serviceId}/repo`**
  * **Request Body:** `{"repoUrl": "https://github.com/...", "branch": "main"}`
  * **Chức năng:** Kiểm tra URL (chỉ `https`, chặn SSRF/địa chỉ nội bộ) và nhánh thông qua `RepoCheckService`, sau đó lưu vào service. Trả về `200 OK` (`RepoResponse`), `400` nếu URL sai, `502` nếu không truy cập được repo.
* **`DELETE /api/services/{serviceId}/uploads/{uploadId}`**
  * **Chức năng:** Xóa bản upload (trả về `204 No Content`).

### 5.2. Kiểm tra Git Repo — `RepoCheckController` (`/api/repos`)

* **`GET /api/repos/check`**
  * **Query params:** `url` (bắt buộc), `branch` (tùy chọn).
  * **Chức năng:** Kiểm tra trước tính hợp lệ và khả năng truy cập của một Git repository công khai (lấy danh sách branch, defaultBranch) trước khi lưu.
  * **Response:** `200 OK` (hợp lệ), `400 Bad Request` (`INVALID_URL`), `502 Bad Gateway` (`UNREACHABLE`).

---

## 6. Triển khai (Deployments), Logs, Rollback & Dọn Image — `DeploymentController`

Base URL: `/api/services/{serviceId}/deployments`

* **`POST /api/services/{serviceId}/deployments`**
  * **Query param:** `?userId={id}` (tùy chọn).
  * **Request Body (tùy chọn):** `{"uploadId": 12}`
    * Nếu có `uploadId`: Deploy từ file ZIP đã upload.
    * Nếu không có body hoặc `uploadId` là `null`: Deploy từ Git repo (`repoUrl` + `branch`) đã gắn trên service.
  * **Chức năng:** Đẩy deployment vào hàng đợi bất đồng bộ (`QUEUED`) để worker nền build & chạy container.
  * **Response (`202 ACCEPTED`):** `{"deploymentId": 21, "status": "QUEUED", "sourceType": "ZIP|GIT"}`
* **`GET /api/services/{serviceId}/deployments`** (`?userId={id}` tùy chọn)
  * **Chức năng:** Liệt kê tối đa 50 deployment gần nhất của service (sắp xếp mới nhất trước).
* **`GET /api/services/{serviceId}/deployments/{deploymentId}`** (`?userId={id}&offset=0`)
  * **Chức năng:** Lấy chi tiết trạng thái và log build tăng dần theo `offset`. Frontend poll định kỳ với `offset = nextOffset` đến khi `finished = true` và `moreLog = false`.
* **`POST /api/services/{serviceId}/deployments/{deploymentId}/rollback`** (`?userId={id}` tùy chọn)
  * **Chức năng:** Rollback service về một deployment cũ đã build thành công (`image != null`, `imagePruned == false`, không phải đang `LIVE`). Tạo deployment mới với `trigger = ROLLBACK`, **bỏ qua bước fetch source và build image**, khởi chạy lại container từ image cũ theo cơ chế zero-downtime.
  * **Response (`202 ACCEPTED`):** `DeployResponse`.
* **`POST /api/services/{serviceId}/deployments/prune`**
  * **Chức năng:** Dọn thủ công các Docker image cũ của service vượt quá hạn mức `deployments_retained` của chủ sở hữu (luôn giữ `LIVE`, các deploy đang chạy và bảo vệ image đang được dùng chung bởi rollback). Đánh dấu `imagePruned = true`.
  * **Response (`200 OK`):** `{"serviceId": 5, "prunedCount": 2, "removedImages": [...]}`.

---

## 7. Biến môi trường (Environment Variables) — `EnvVariableController`

Base URL: `/api/services/{serviceId}/env`

* **`PUT /api/services/{serviceId}/env`**
  * **Request Body:** `{"key": "DB_PASS", "value": "secret123"}`
  * **Chức năng:** Tạo mới (`201 Created`) hoặc cập nhật (`200 OK`) một biến môi trường. Value được mã hóa AES-GCM trước khi lưu DB và **không bao giờ** trả về trong response.
  * **Response:** `{"key": "DB_PASS", "created": true}`
* **`GET /api/services/{serviceId}/env`**
  * **Chức năng:** Liệt kê danh sách tên biến (`keys`) của service — chỉ trả về key, không bao giờ lộ value.
  * **Response (`200 OK`):** `{"keys": ["DB_PASS", "PORT"]}`
* **`DELETE /api/services/{serviceId}/env/{key}`**
  * **Chức năng:** Xóa 1 biến môi trường (idempotent). Trả về `204 No Content`.

---

## 8. Dịch vụ Database (PostgreSQL) — `DatabaseController`

Base URL: `/api/services/{serviceId}/database`

* **`POST /api/services/{serviceId}/database/provision`** (`?userId={id}` tùy chọn)
  * **Chức năng:** Tạo Docker volume, khởi chạy container PostgreSQL, sinh mật khẩu ngẫu nhiên và trả về thông tin kết nối (`host`, `port`, `database`, `username`, `password`, `databaseUrl`, `volumeBytes`).
* **`POST /api/services/{serviceId}/database/start`** (`?userId={id}` tùy chọn)
  * **Chức năng:** Khởi động lại database container đã dừng.
* **`POST /api/services/{serviceId}/database/stop`** (`?userId={id}` tùy chọn)
  * **Chức năng:** Dừng database container (`{"status": "STOPPED"}`).
* **`GET /api/services/{serviceId}/database/info`** (`?userId={id}` tùy chọn)
  * **Chức năng:** Xem thông tin kết nối và credentials hiện tại của database service.
* **`POST /api/services/{serviceId}/database/link/{targetAppId}`** (`?userId={id}` tùy chọn)
  * **Chức năng:** Liên kết Database với một `APP` service cùng project bằng cách tự động nạp biến môi trường `DATABASE_URL` vào app đích.
* **`GET /api/services/{serviceId}/database/logs`** (`?lines=100&userId={id}`)
  * **Chức năng:** Xem logs của container PostgreSQL (`{"logs": "..."}`).

---

## 9. Quản trị hệ thống (Admin) — `AdminController`

Base URL: `/api/admin` *(Yêu cầu đăng nhập và email nằm trong cấu hình `DEPLOYRA_ADMIN_EMAILS`, được kiểm tra qua `AdminGuard`)*

* **`GET /api/admin/overview`**: Thống kê tổng quan hệ thống (`users`, `projects`, `runningServices`, `failedServices`).
* **`GET /api/admin/users`**: Danh sách toàn bộ người dùng kèm gói hiện tại và trạng thái kích hoạt.
* **`GET /api/admin/users/{id}`**: Chi tiết một người dùng và danh sách ghi đè hạn mức (`overrides`).
* **`PATCH /api/admin/users/{id}/active`**: Khóa hoặc mở khóa tài khoản người dùng (`{"active": true|false}`).
* **`PUT /api/admin/users/{id}/overrides`**: Ghi đè hạn mức riêng (`UserEntitlementOverride`) cho một người dùng (`entitlementKey`, `limitValue`, `reason`, `expiresAt`).
* **`DELETE /api/admin/users/{id}/overrides/{key}`**: Xóa ghi đè hạn mức (`204 No Content`).
* **`GET /api/admin/services`**: Danh sách toàn bộ service trên toàn hệ thống kèm thông tin chủ sở hữu, trạng thái và lý do tạm ngưng (`suspendedReason`).
* **`GET /api/admin/entitlement-keys`**: Danh sách toàn bộ các khóa hạn mức (`EntitlementKey`) trong hệ thống.
