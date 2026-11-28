# Báo cáo tích hợp Deployra-System

Ngày 29/11/2026 · Nhánh đích `minhanh` · Nhánh nguồn `origin/TranKhanhAn`

## Trạng thái Git

Đã giải quyết conflict trong `pom.xml`, `SecurityConfig.java` và `application.properties`. Tại thời điểm lập báo cáo, Git đã ghi nhận hết conflict và đang chờ tạo merge commit. Các thay đổi Java và test đã được stage. Cấu hình Traefik metrics cổng `9100` là thay đổi local chưa được stage.

## Phần Người 2 sau khi tích hợp

| Hạng mục | Thay đổi |
| --- | --- |
| Tài khoản và xác thực | Gộp đăng ký vào `api/AuthController`, giữ logic `AuthService`, BCrypt và bổ sung đăng nhập, đăng xuất theo JWT. `POST /api/auth/register` trả `201` với `id` và `email`. JWT được thu hồi khi đăng xuất. `SecurityConfig` giữ phân quyền `ROLE_USER` và `ROLE_ADMIN`. |
| Hồ sơ người dùng | `GET /api/me` dùng `MeController` và `MeResponse` của nhánh mới, lấy ID tài khoản từ JWT và dùng `UserService` để dựng response. Bỏ `GET /api/me/subscription` vì thông tin mã và tên gói đã có trong báo cáo quota. |
| Quota và cảnh báo RAM | `GET /api/me/quota` giữ `QuotaDtos.Report` với `planCode`, `planName`, `lines` và `activeAddons`. `QuotaService` nhận ID từ JWT và dùng `EntitlementService` để tính hạn mức. Cảnh báo RAM chuyển sang `GET /api/me/quota/warning`. |
| Mua add-on | Giữ URL của nhánh mới `POST /api/me/addons/{packageId}/purchase` và chuyển sang luồng VNPay. `QuotaService` kiểm tra kỳ trả phí còn hiệu lực, tạo `Payment` ở trạng thái `PENDING` và trả `paymentUrl`. Add-on được tạo khi IPN xác nhận thành công. `addonId` trong response khởi tạo hiện là ID của gói add-on, còn `expiresAt` chưa có ở bước này. |
| Mua hoặc đổi gói | Giữ `POST /api/subscribe` và `GET /api/payments/vnpay/ipn`. Khi IPN thành công, backend cập nhật thanh toán, xử lý kỳ cũ và kích hoạt gói mới. Giá mẫu và giao dịch VNPay dùng VND. |
| Job hết hạn | Chuyển hai job sang package `job`. `SubscriptionExpiryJob` đưa tài khoản về Free khi kỳ trả phí hết hạn. `RamAddonExpiryJob` kiểm tra lại quota sau thời gian chờ 24 giờ, ưu tiên service có lần deploy `LIVE` gần nhất, gọi runtime tương ứng cho app hoặc database và ghi `SUSPENDED` với lý do `MEMORY_OVER_QUOTA`. |
| API quản trị | Giữ `AdminController`, DTO và luồng override của nhánh mới. Endpoint hiện tại là `PUT /api/admin/users/{id}/overrides`. Controller và service override riêng của Người 2 đã được dọn khỏi bản tích hợp. |
| Dữ liệu mẫu và test | Giữ dữ liệu danh mục, thêm seeder demo từ nhánh mới và bỏ dependency Hibernate `Environment` không dùng trong `DataSeeder`. Cập nhật `AuthControllerTest` theo `AuthService` và `saveAndFlush()`. Test email trùng kiểm tra HTTP `409` theo API hiện tại. |

## Phần nhận từ nhánh TranKhanhAn

- Backend có thêm API project, service, deployment, database, biến môi trường, kiểm tra repo, upload ZIP và dashboard admin.
- Bổ sung deploy engine, Docker runtime, lưu trữ source local và các test liên quan.
- Thêm frontend React/Vite, Dockerfile, stack Compose, cấu hình Flyway, CI và tài liệu triển khai.
- POM sau khi gộp giữ dependency JWT và Flyway để phục vụ cả hai phần.

## Điểm cần xem xét tiếp

- **Đồng bộ JWT với frontend và `CurrentUser`.** Frontend vẫn gửi HTTP Basic, còn backend đã chuyển sang Bearer JWT. `ProjectApiService`, `ServiceApiService` và `AdminGuard` vẫn gọi `CurrentUser.require()` theo luồng cũ. Khi chuyển sang `@AuthenticationPrincipal Long userId`, cần truyền ID từ controller xuống service và giữ kiểm tra quyền sở hữu. `ProjectController` vẫn có chỗ nhận ID từ query hoặc body và có fallback chọn user đầu tiên. Rà lại các handler Project theo ID từ JWT. Với Admin, thống nhất cách dùng `ROLE_ADMIN` và danh sách email của `AdminGuard`.
- **Nối frontend với VNPay.** Trang Quota vẫn gọi đúng URL mua add-on và cần đọc `paymentUrl` để chuyển người dùng sang Sandbox. Sau thanh toán, tải lại quota để hiển thị add-on đã kích hoạt. Khi cập nhật kiểu dữ liệu frontend, lưu ý `expiresAt` có thể chưa có trong response khởi tạo.
- **Kiểm tra dừng service bằng Docker thật.** Job RAM đã gọi runtime, nhưng chưa xác nhận luồng với container thật. `DockerDatabaseRuntime.stop()` hiện gọi `exec()` và chưa kiểm tra mã thoát của lệnh dừng, nên cần xử lý trường hợp Docker báo lỗi trước khi coi database đã dừng.
- **Hoàn thiện bandwidth.** Local đã đọc được `/metrics` của Traefik. Bước tiếp theo là xác nhận bộ đếm byte theo router của app, ghi phần byte tăng thêm vào `bandwidth_usage_daily`, áp dụng `BANDWIDTH_EXCEEDED` và mở lại service khi sang tháng. Code ghi bandwidth thử trước merge vẫn nằm trong stash.
- **Chốt cấu hình chạy chung.** `application.properties` còn import file VNPay theo đường dẫn riêng của máy dev. Cần thống nhất cách cấp cấu hình JWT và VNPay cho các máy khác. Trước khi dùng profile `prod`, đối chiếu migration với các entity mới. `DataSeeder` đang tạo admin demo với mật khẩu cố định, nên nhóm cần giới hạn phần seed này cho môi trường demo trước khi triển khai.

## Kết quả kiểm thử

- Maven `BUILD SUCCESS` với **169 test, 0 thất bại, 0 lỗi, 0 bỏ qua**.
- Traefik local đã chạy và `http://127.0.0.1:9100/metrics` đã trả dữ liệu. Bộ đếm byte theo router của một app và luồng tích hợp qua frontend vẫn cần kiểm tra.
