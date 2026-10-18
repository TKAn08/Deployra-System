# Phân công công việc: Deployra (3 người)

Deployra là PaaS thu nhỏ kiểu Railway: người dùng deploy từ GitHub repo hoặc file zip, hệ thống build bằng Dockerfile và chạy container. Bản đầu chạy local, sau đó lên 1 máy AWS EC2. Có gói Free/Starter/Pro và add-on mua thêm RAM, bandwidth.

## 1. Hiện trạng (đã làm sẵn)

| Phần | Nơi | Ghi chú |
|---|---|---|
| 15 entity (users, gói, hạn mức, add-on, project, service, deployment, upload, biến, bandwidth) | `account/`, `entitlement/`, `paas/` | Bảng `nodes` chưa tạo, chờ khi có node thứ 2 |
| 15 `JpaRepository` | cùng package với entity | Đã có sẵn các truy vấn tổng (RAM, bandwidth, storage, phút build) |
| `EntitlementService` | `entitlement/` | Tính hạn mức = (override ?? gói ?? mặc định) + add-on; có `requireCan...` để chặn thao tác vượt gói |
| `DataSeeder` | `seed/` | Tự nạp 17 khoá hạn mức, 3 gói, 2 add-on khi khởi động |
| `SecretCipher`, `EnvVariableService` | `common/`, `paas/` | Mã hoá AES-GCM cho biến môi trường |
| `GET /api/plans`, `GET /api/addons` | `catalog/` | Công khai cho trang bảng giá |
| 2 interface chờ cài đặt: `ContainerRuntime`, `SourceStorage` | `runtime/`, `storage/` | Hợp đồng giữa các phần, xem mục 5 |
| `SecurityConfig` tạm (HTTP Basic) | `config/` | Người 2 thay bằng đăng nhập thật |

**Lưu ý:** code trên được viết trong môi trường không tải được thư viện Maven nên chưa được biên dịch hay chạy test. Việc đầu tiên của cả nhóm là chạy `./mvnw verify` (Windows: `mvnw.cmd verify`) rồi sửa lỗi nếu có.

## 2. Nguyên tắc chia

Mỗi người sở hữu một số package riêng để ít đụng file nhau. Cần sửa package của người khác thì nhắn trước. Riêng **sửa entity (thêm/đổi cột) phải báo cả nhóm** vì ảnh hưởng DB chung.

| | Người 1: Deploy engine | Người 2: Tài khoản, thanh toán, hạn mức | Người 3: API, giao diện, hạ tầng |
|---|---|---|---|
| Sở hữu | `runtime/`, `deploy/` (mới) | `account/`, `entitlement/`, `billing/` (mới) | `api/` (mới), `storage/`, `catalog/`, `frontend/` (mới) |

## 3. Người 1: Deploy engine

Mục tiêu: nhận một service và một nguồn mã, chạy ra container có URL.

- [ ] Clone repo: chỉ `https`, theo branch hoặc commit SHA, chặn địa chỉ nội bộ (SSRF)
- [ ] Giải nén zip an toàn: chống zip-slip, giới hạn dung lượng sau giải nén
- [ ] `DockerContainerRuntime` (gợi ý thư viện docker-java): build, chạy với giới hạn RAM/CPU theo `memoryMb`/`cpuMillicores`, nhãn Traefik, healthcheck, dừng/bật/xoá, log, thống kê
- [ ] `DeploymentService`: hàng đợi và máy trạng thái `QUEUED → BUILDING → DEPLOYING → LIVE/FAILED`
  - Mỗi service chỉ 1 deploy chạy tại một thời điểm; deploy cũ đang chờ bị huỷ khi có deploy mới
  - Ghi `buildLog`, `buildSeconds`, `imageBytes`, `commitSha`
  - Zero-downtime: container mới khoẻ rồi mới xoá container cũ
- [ ] Rollback bằng image cũ, dọn image theo `deployments_retained` (đặt `imagePruned = true`)
- [ ] Service kiểu DATABASE: container Postgres + volume, đưa `DATABASE_URL` vào app cùng project
- [ ] Webhook GitHub xác thực HMAC `X-Hub-Signature-256`, chỉ deploy khi gói có `auto_deploy`
- [ ] Job auto-sleep cho gói Free (dựa vào `lastActiveAt`)
- [ ] Test bằng mock `ContainerRuntime`

## 4. Người 2: Tài khoản, thanh toán, hạn mức

Mục tiêu: biết ai đăng nhập, ai trả tiền gói nào, và chặn đúng khi vượt hạn mức.

- [ ] Đăng ký / đăng nhập (đã có bean BCrypt), JWT hoặc session; thay `SecurityConfig` tạm
- [ ] Luồng mua gói: `Payment → UserSubscription → users.subscription_plan_id` trong **một transaction**, xử lý trùng theo `providerTxnId`. Làm cổng thanh toán giả lập trước
- [ ] Mua add-on: tính `expiresAt` (30 ngày hoặc cuối tháng dương lịch), chỉ gói trả phí được mua, mỗi thanh toán tạo đúng một `UserAddon`
- [ ] Job hết hạn gói (về Free) và add-on RAM hết hạn: báo trước, cho vài ngày chờ, rồi dừng service deploy gần nhất cho đến khi vừa pool RAM
- [ ] Đo bandwidth: đọc metrics Traefik theo router `svc<id>` ghi vào `bandwidth_usage_daily`; vượt hạn mức thì `SUSPENDED` (`BANDWIDTH_EXCEEDED`), sang tháng mở lại
- [ ] API admin ghi đè hạn mức (`UserEntitlementOverride`)
- [ ] `GET /api/me/quota` dùng `EntitlementService.quotaReport`
- [ ] Mở rộng `EntitlementServiceTest` khi thêm luật mới

## 5. Người 3: API, giao diện, hạ tầng

Mục tiêu: người dùng thao tác được qua web, và hệ thống chạy được trên EC2.

- [ ] REST: projects, services, deployments, variables, upload zip, log. Dùng DTO + validation, không trả entity ra ngoài
- [ ] Trước mỗi thao tác gọi `EntitlementService.requireCan...` (lỗi vượt hạn mức đã tự thành HTTP 403 qua `ApiExceptionHandler`)
- [ ] `LocalSourceStorage` trước, `S3SourceStorage` sau (IAM Role, không lưu key vào DB)
- [ ] Frontend React + Vite: đăng nhập, danh sách project, trang service (log build, poll 1,5 giây), bảng giá từ `/api/plans`, trang hạn mức và mua thêm
- [ ] Dockerfile cho app, `docker-compose` (Postgres, Traefik), CI chạy `./mvnw verify`
- [ ] Triển khai EC2 (t3.medium): security group, IAM Role, domain wildcard
- [ ] Chuyển từ `ddl-auto=update` sang Flyway + `validate` trước khi lên máy thật

## 6. Hợp đồng giữa các phần (chốt trong buổi đầu)

| Hợp đồng | Bên cài đặt | Bên dùng |
|---|---|---|
| `ContainerRuntime` | Người 1 | `DeploymentService` (Người 1), job của Người 2 (dừng/ngủ service) |
| `SourceStorage` | Người 3 | `DeploymentService` (đọc zip), API upload |
| `DeploymentService.enqueue(service, trigger)` (Người 1 viết chữ ký trong ngày đầu) | Người 1 | API (Người 3), webhook, rollback |
| `EntitlementService.requireCan...`, `quotaReport` | đã có (Người 2 mở rộng) | API (Người 3), deploy (Người 1) |
| Người đang đăng nhập (`userId`) | Người 2 | Mọi API của Người 3 |

Mỗi người dùng mock cho hợp đồng chưa xong, đừng chờ nhau.

## 7. Thứ tự làm

| Giai đoạn | Người 1 | Người 2 | Người 3 |
|---|---|---|---|
| 0. Cả nhóm (1 buổi) | Chạy `./mvnw verify`, chạy được Postgres local, thống nhất danh sách endpoint | | |
| 1. Nền | Clone + build + chạy 1 app bằng test, chưa qua API | Đăng ký/đăng nhập, `/api/me` | CRUD project/service, khung frontend |
| 2. Ghép | Deploy qua API, rollback, database service | Mua gói + add-on (cổng giả lập) | Upload zip, log realtime, trang bảng giá |
| 3. Hạn mức | Gắn `requireCan...` vào luồng deploy, auto-sleep | Đo bandwidth, suspend, job hết hạn | Trang hạn mức, báo lỗi vượt gói |
| 4. Hoàn thiện | Webhook, dọn image | Admin override | EC2, Flyway, demo end-to-end |

## 8. Quy ước làm việc

- Nhánh: `feature/<tên>-<việc>`, PR nhỏ (dưới khoảng 400 dòng), merge vào `main` khi được người khác duyệt
- Review chéo: Người 1 duyệt Người 3, Người 3 duyệt Người 2, Người 2 duyệt Người 1
- Trước khi mở PR: `./mvnw verify` phải xanh
- Không commit `.env`, khoá mã hoá, key AWS
- Việc nào xong thì tích `[x]` trong file này

## 9. Chạy thử

```bash
docker compose up -d postgres                  # DB local
export DEPLOYRA_CRYPTO_KEY=$(openssl rand -base64 32)
./mvnw spring-boot:run                         # lần đầu tự tạo bảng và nạp gói/hạn mức
curl localhost:8080/api/plans                  # xem 3 gói
./mvnw verify                                  # test bằng H2, không cần Postgres
```
