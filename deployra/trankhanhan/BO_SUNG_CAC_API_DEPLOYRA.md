# Bổ Sung Các API Còn Thiếu Theo Đúng `PHAN_CONG.md` — Tránh Conflict Trong Nhóm

Tài liệu này ghi lại chi tiết các API được bổ sung vào dự án Deployra theo đúng nguyên tắc và phân công trong file `PHAN_CONG.md`, đảm bảo tính hoàn chỉnh cho hệ thống PaaS mà **không gây xung đột (conflict) git với công việc của các thành viên khác**.

---

## 1. Đối Chiếu Với `PHAN_CONG.md` Và Nguyên Tắc Tránh Conflict

File `PHAN_CONG.md` phân định rõ 3 thành viên:

| Thành viên | Trách nhiệm chính | Package sở hữu | Việc cần làm theo `PHAN_CONG.md` |
| :--- | :--- | :--- | :--- |
| **Người 1: Deploy engine** | Nền tảng thực thi & Docker | `runtime/`, `deploy/` | `DockerContainerRuntime`, `DeploymentService`, Postgres database service, rollback, webhook HMAC, auto-sleep. |
| **Người 2: Tài khoản & Hạn mức** | Auth, thanh toán, hạn mức | `account/`, `entitlement/`, `billing/` | Đăng ký/đăng nhập JWT/session, mua gói/add-on, đo bandwidth, `GET /api/me/quota`. |
| **Người 3: API & Hạ tầng** | REST API, Frontend, Dockerfile | `api/`, `storage/`, `catalog/`, `frontend/` | REST: **projects, services, deployments, variables, upload zip, log**. Dùng DTO + validation, gọi `EntitlementService.requireCan...`, Dockerfile, docker-compose. |

### Các nguyên tắc tư duy để tránh conflict:
1. **Tuyệt đối không can thiệp vào package `account/`, `entitlement/`, `billing/`**:
   - Đây là các package thuộc sở hữu của **Người 2**.
   - Không sửa `UserRepository.java`, không tự ý thêm các endpoint auth hay `GET /api/me/quota` vào nhánh này (Người 2 sẽ tự triển khai `GET /api/me/quota` và quản lý token).
2. **Không sửa đổi cấu trúc Entity (`paas/`, `account/`)**:
   - Giữ nguyên các entity để không làm thay đổi schema database chung của nhóm.
   - Không sửa đổi các interface repository (`ProjectRepository.java`, `AppServiceRepository.java`), tận dụng các query kế thừa từ `JpaRepository` sẵn có (`findAll()`, `findById()`, `filter()`).
3. **Giữ nguyên toàn bộ những gì nhánh đã làm**:
   - `UploadController` (upload zip, git repo).
   - `DeploymentController` (deploy zip/git, list deployments, stream build log theo offset).
   - `EnvVariableController` (CRUD key biến môi trường, bảo mật AES-GCM).
   - `DatabaseController` (cấp phát, start/stop, xem log, link PostgreSQL).
   - `CatalogController` (plans, addons).
   - `RepoCheckController` (kiểm tra git repo).
4. **Bổ sung đúng những phần còn thiếu trong checklist REST của Người 3**:
   - Trước đó chưa có REST controller cho `projects` -> Bổ sung **`ProjectController`**.
   - Chưa có REST controller cho `services` (tạo app/database service, cập nhật cấu hình, start/stop/restart container, lấy live logs và metrics) -> Bổ sung **`AppServiceController`**.

---

## 2. Chi Tiết Các API Mới Được Bổ Sung

Tất cả các API mới đều nằm độc lập trong package `com.paas.deployra.api`:

### 2.1. Quản lý Dự Án — `ProjectController` (`/api/projects`)

Sở hữu bởi tầng API (Người 3), gọi `EntitlementService.requireCanCreateProject` (hợp đồng với Người 2):

| Method | Endpoint | Chức năng | Request Body / Param | Response Code |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/projects` | Danh sách project (kèm số service bên trong) | `?userId={id}` (tùy chọn) | `200 OK` |
| `GET` | `/api/projects/{projectId}` | Xem chi tiết 1 project kèm danh sách các service | Không | `200 OK` |
| `POST` | `/api/projects` | Tạo mới project (kiểm tra hạn mức `max_projects`) | `{"name": "...", "userId": ...}` | `201 CREATED` |
| `PUT` | `/api/projects/{projectId}` | Đổi tên project | `{"name": "..."}` | `200 OK` |
| `DELETE` | `/api/projects/{projectId}` | Xóa mềm project và các service trực thuộc | Không | `204 NO CONTENT` |

#### Mẫu JSON:
- **Tạo Project (`POST /api/projects`)**:
  ```json
  {
    "name": "My First Project",
    "userId": 3
  }
  ```
- **Response (`201 CREATED`)**:
  ```json
  {
    "id": 1,
    "name": "My First Project",
    "ownerId": 3,
    "createdAt": "2026-11-22T15:00:00Z",
    "serviceCount": 0
  }
  ```

---

### 2.2. Quản lý Service & Vòng Đời Container — `AppServiceController`

Tích hợp gọi hợp đồng `ContainerRuntime` (Người 1) và `EntitlementService` (Người 2):

| Method | Endpoint | Chức năng | Request Body / Param | Response Code |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/projects/{projectId}/services` | Danh sách các service trong 1 project | Không | `200 OK` |
| `POST` | `/api/projects/{projectId}/services` | Tạo service mới (`APP` hoặc `DATABASE`) | JSON cấu hình service | `201 CREATED` |
| `GET` | `/api/services/{serviceId}` | Chi tiết service, cấu hình RAM/CPU và URL truy cập | Không | `200 OK` |
| `PUT` | `/api/services/{serviceId}` | Cập nhật cấu hình service (port, RAM, CPU, branch,...) | JSON cập nhật | `200 OK` |
| `DELETE` | `/api/services/{serviceId}` | Xóa mềm service và gọi `runtime.remove(service)` | Không | `204 NO CONTENT` |
| `POST` | `/api/services/{serviceId}/start` | Khởi động service container (kiểm tra pool RAM) | `?userId={id}` (tùy chọn) | `200 OK` |
| `POST` | `/api/services/{serviceId}/stop` | Dừng service container để trả lại RAM cho pool | `?userId={id}` (tùy chọn) | `200 OK` |
| `POST` | `/api/services/{serviceId}/restart` | Khởi động lại service container | `?userId={id}` (tùy chọn) | `200 OK` |
| `GET` | `/api/services/{serviceId}/logs` | Lấy live log từ container Docker | `?tailLines=100` (mặc định 100) | `200 OK` |
| `GET` | `/api/services/{serviceId}/metrics` | Đọc CPU % và RAM bytes thực tế qua Docker stats | Không | `200 OK` |

#### Mẫu JSON:
- **Tạo Service (`POST /api/projects/1/services`)**:
  ```json
  {
    "name": "web-frontend",
    "type": "APP",
    "port": 80,
    "memoryMb": 256,
    "cpuMillicores": 500,
    "rootDir": ".",
    "dockerfilePath": "Dockerfile",
    "repoUrl": "https://github.com/example/react-app",
    "branch": "main"
  }
  ```
- **Response (`201 CREATED`)**:
  ```json
  {
    "id": 10,
    "projectId": 1,
    "name": "web-frontend",
    "slug": "web-frontend-2kyhoa",
    "type": "APP",
    "status": "IDLE",
    "accessUrl": "http://web-frontend-2kyhoa.localhost",
    "port": 80,
    "healthcheckPath": "/",
    "memoryMb": 256,
    "cpuMillicores": 500,
    "repoUrl": "https://github.com/example/react-app",
    "branch": "main",
    "lastActiveAt": null,
    "createdAt": "2026-11-22T15:05:00Z"
  }
  ```
- **Đọc Live Metrics (`GET /api/services/10/metrics`)**:
  ```json
  {
    "serviceId": 10,
    "serviceName": "web-frontend",
    "slug": "web-frontend-2kyhoa",
    "cpuPercent": 0.32,
    "memoryBytes": 16777216,
    "memoryMb": 16
  }
  ```

---

## 3. Tổng Hợp Tình Trạng Toàn Bộ API Sau Khi Hoàn Thiện

| Nhóm API | Controller | Trạng thái |
| :--- | :--- | :--- |
| **Projects** (CRUD) | `ProjectController` | **Mới bổ sung** (chuẩn mục 5 `PHAN_CONG.md`) |
| **Services & Runtime** (CRUD + Start/Stop/Logs/Metrics) | `AppServiceController` | **Mới bổ sung** (chuẩn mục 5 `PHAN_CONG.md`) |
| **Deployments** (Enqueue Git/Zip, List, Logs offset) | `DeploymentController` | **Đã có sẵn**, giữ nguyên |
| **Uploads & Git Repo** (Upload zip, set repo) | `UploadController` | **Đã có sẵn**, giữ nguyên |
| **Environment Variables** (CRUD biến môi trường AES-GCM) | `EnvVariableController` | **Đã có sẵn**, giữ nguyên |
| **Database PostgreSQL** (Provision, start/stop, link app) | `DatabaseController` | **Đã có sẵn**, giữ nguyên |
| **Repo Validation** (Check URL repo GitHub) | `RepoCheckController` | **Đã có sẵn**, giữ nguyên |
| **Pricing & Catalog** (Plans, Addons) | `CatalogController` | **Đã có sẵn**, giữ nguyên |
| **Auth & Quota** (`/api/me/quota`, JWT/Session) | *Dành cho Người 2* | **Không can thiệp** (tránh conflict theo mục 4) |

---

## 4. Kiểm Thử Nhanh Qua Lệnh cURL

```bash
# 1. Liệt kê danh sách project
curl http://localhost:8080/api/projects

# 2. Tạo một project mới
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "E-Commerce App", "userId": 1}'

# 3. Tạo một service mới trong project 1
curl -X POST http://localhost:8080/api/projects/1/services \
  -H "Content-Type: application/json" \
  -d '{"name": "frontend-web", "type": "APP", "port": 80, "memoryMb": 256}'

# 4. Xem runtime logs của container service 1
curl http://localhost:8080/api/services/1/logs?tailLines=50

# 5. Xem metrics CPU và RAM thực tế của service 1
curl http://localhost:8080/api/services/1/metrics
```
