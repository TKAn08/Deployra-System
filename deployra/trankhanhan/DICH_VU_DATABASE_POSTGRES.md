9998*9fdasc# Dịch vụ Database PostgreSQL trên Deployra

Tài liệu này tổng hợp toàn bộ phần thiết kế, mã nguồn mới và hướng dẫn sử dụng tính năng **Database Service (PostgreSQL)** được bổ sung cho Deployra.

Toàn bộ logic được **tách thành các class/file mới độc lập**, không làm phình các class cũ (`DockerContainerRuntime`, `DeploymentService` vốn đã dài).

---

## 1. Kiến trúc Database Service vs App Service

| Tiêu chí | App Service thông thường (`APP`) | Database Service (`DATABASE`) |
| :--- | :--- | :--- |
| **Nguồn triển khai** | Build từ Git hoặc ZIP qua Dockerfile | Dùng image chính thức: `postgres:16-alpine` (không qua build) |
| **Lưu trữ dữ liệu** | Stateless (không lưu ổ đĩa vĩnh viễn) | **Stateful**: Mount **Docker Volume** (`deployra-vol-svc-<id>`) vào `/var/lib/postgresql/data` |
| **Định tuyến mạng** | HTTP/HTTPS qua Traefik router | Kết nối TCP cổng `5432` nội bộ qua Docker network `deployra` |
| **Zero-downtime** | Container mới healthy $\rightarrow$ xoá container cũ | **Không chạy 2 container cùng lúc** (sẽ hỏng volume), quy trình Stop $\rightarrow$ Start |
| **Biến môi trường** | Nhận `DATABASE_URL` để kết nối | Tự sinh credentials và **cung cấp `DATABASE_URL`** cho App khác |

---

## 2. Danh sách file mới đã tạo

### 2.1. Mã nguồn chính (`src/main/java/com/paas/deployra/`)

1. **`runtime/DatabaseRuntime.java`**:
   - Interface hợp đồng dành riêng cho Database.
   - Định nghĩa các hàm: `startPostgres`, `waitHealthy`, `stop`, `start`, `remove`, `logs`, `getVolumeSizeBytes`.
   - Chứa record `DatabaseConfig` (image, dbName, user, pass, port, RAM, CPU, extraEnv).

2. **`runtime/DockerDatabaseRuntime.java`**:
   - Cài đặt `DatabaseRuntime` tương tác trực tiếp với Docker daemon CLI.
   - **Tự động quản lý Volume:** Tạo `deployra-vol-svc-<serviceId>` nếu chưa có và mount vào container.
   - **Tên container & DNS nội bộ:** Đặt tên container cố định `deployra-db-<slug>`, alias mạng là `<slug>`. Các container App khác cùng network `deployra` có thể kết nối trực tiếp qua hostname này.
   - **Healthcheck chuyên biệt:** Sử dụng lệnh `docker exec <containerId> pg_isready -U <user> -d <db> -h 127.0.0.1 -p <port>` định kỳ 1s/lần cho đến khi sẵn sàng.
   - **Bảo mật:** Biến môi trường truyền qua file tạm 0600 (xoá ngay sau khi chạy), giới hạn RAM (`--memory`), CPU (`--cpus`), số tiến trình (`--pids-limit 256`).

3. **`deploy/DatabaseProvisionService.java`**:
   - Tầng nghiệp vụ xử lý logic Database:
     - Kiểm tra quyền sở hữu service của user.
     - Kiểm tra quota RAM trong gói (`entitlements.requireCanStartService`).
     - Tự động sinh `POSTGRES_DB`, `POSTGRES_USER` (`postgres`), và `POSTGRES_PASSWORD` (chuỗi ngẫu nhiên 20 ký tự an toàn) nếu chưa có.
     - Mã hóa và lưu các credentials vào `EnvVariableService`.
     - Tự động định dạng và lưu chuỗi kết nối:
       `postgresql://<user>:<password>@deployra-db-<slug>:5432/<dbname>`
     - Cập nhật trạng thái `AppService.status = RUNNING` và lưu dung lượng volume.
     - Cung cấp tính năng **`linkToApp`**: Tự động inject biến `DATABASE_URL` vào một APP service cùng project.

4. **`api/DatabaseController.java`**:
   - Cung cấp các REST endpoint cho Database Service dưới tiền tố:
     `/api/services/{serviceId}/database`

### 2.2. Kiểm thử (`src/test/java/com/paas/deployra/`)

5. **`deploy/DatabaseProvisionServiceTest.java`**:
   - Kiểm tra cấp phát thành công, sinh mật khẩu, kiểm tra quyền sở hữu, chặn service không phải database, test dừng database, test link `DATABASE_URL` giữa DB và App trong cùng project, và từ chối khi khác project.

6. **`api/DatabaseControllerTest.java`**:
   - MockMvc standalone test toàn bộ các HTTP endpoint (`/provision`, `/stop`, `/info`, `/link/{targetId}`, mã lỗi 400, 404).

### 2.3. Sửa chữa / Khôi phục
7. **`seed/DataSeeder.java`**:
   - Khôi phục file `DataSeeder.java` (trước đó bị xoá trong working tree) để đảm bảo các bảng hạn mức, gói cước và add-on được nạp đầy đủ khi khởi động.

---

## 3. Chi tiết các REST API mới

Mọi request thao tác trên service database đều nằm ở tiền tố `/api/services/{serviceId}/database`:

### 3.1. Cấp phát & Khởi chạy (`POST /provision`)
Khởi tạo Volume, chạy container Postgres và chờ đến khi sẵn sàng.

```bash
curl -X POST localhost:8080/api/services/{serviceId}/database/provision
```

**Response (200 OK):**
```json
{
  "serviceId": 10,
  "serviceName": "postgres-prod",
  "slug": "postgres-prod-a1b2c3",
  "host": "deployra-db-postgres-prod-a1b2c3",
  "port": 5432,
  "database": "db_postgres_prod_a1b2c3",
  "username": "postgres",
  "password": "sOmEsEcReTpAsSwOrD123",
  "databaseUrl": "postgresql://postgres:sOmEsEcReTpAsSwOrD123@deployra-db-postgres-prod-a1b2c3:5432/db_postgres_prod_a1b2c3",
  "status": "RUNNING",
  "volumeBytes": 0
}
```

---

### 3.2. Lấy thông tin kết nối & Credentials (`GET /info`)
Xem thông tin host, port, database, user và mật khẩu (đã giải mã).

```bash
curl localhost:8080/api/services/{serviceId}/database/info
```

---

### 3.3. Dừng Database (`POST /stop`)
Dừng container an toàn, giữ nguyên toàn bộ dữ liệu trong Docker Volume.

```bash
curl -X POST localhost:8080/api/services/{serviceId}/database/stop
```

**Response (200 OK):**
```json
{
  "status": "STOPPED"
}
```

---

### 3.4. Bật lại Database (`POST /start`)
Khởi động lại container database đã dừng trước đó.

```bash
curl -X POST localhost:8080/api/services/{serviceId}/database/start
```

---

### 3.5. Liên kết Database với App Service cùng Project (`POST /link/{targetAppId}`)
Tự động lấy `DATABASE_URL` của Database này và ghi đè/tạo mới vào danh sách biến môi trường của App service `targetAppId`.

```bash
curl -X POST localhost:8080/api/services/{dbServiceId}/database/link/{appServiceId}
```

**Response (200 OK):**
```json
{
  "linked": true,
  "databaseServiceId": 10,
  "targetAppServiceId": 12,
  "message": "Đã truyền biến DATABASE_URL vào service đích thành công"
}
```
*Lưu ý: Hệ thống sẽ chặn và trả lỗi 400 nếu 2 service không nằm trong cùng một Project.*

---

### 3.6. Xem logs của Database (`GET /logs`)
```bash
curl "localhost:8080/api/services/{serviceId}/database/logs?lines=100"
```

---

## 4. Cách các Web/App Container kết nối vào PostgreSQL

Vì cả Database container và Web App container đều chạy trong cùng một Docker Network (`deployra`):
- Khi App container khởi động, nó nhận biến môi trường `DATABASE_URL` (ví dụ từ bước `linkToApp`).
- Trong chuỗi `DATABASE_URL`, hostname chính là `deployra-db-<slug>`.
- Docker daemon tự động phân giải hostname này về đúng IP nội bộ của container Postgres. Không cần mở cổng ra máy chủ và không cần thông qua Traefik.
