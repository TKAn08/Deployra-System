# PaaS — Chính sách gói dịch vụ và quản lý tài nguyên

## 1. Tổng quan

Tài liệu này mô tả nguyên tắc thiết kế gói dịch vụ, quản lý tài nguyên và thực thi hạn mức cho nền tảng PaaS.

Mục tiêu:

- Cho phép người dùng triển khai ứng dụng ở gói miễn phí.
- Khuyến khích nâng cấp thông qua các tính năng tiện lợi và hạn mức tài nguyên cao hơn.
- Kiểm soát chi phí hạ tầng và hạn chế lạm dụng tài nguyên.
- Không tự động xóa dữ liệu người dùng khi hạ gói dịch vụ.
- Tự động quản lý vòng đời ứng dụng để tối ưu tài nguyên máy chủ.

## 2. Nguyên tắc thiết kế

### 2.1. Gói Free phải tự động giải phóng tài nguyên

Ứng dụng thuộc gói Free không được mặc định chạy liên tục vô thời hạn.

Hệ thống cần có cơ chế tự động dừng ứng dụng khi đáp ứng điều kiện không hoạt động đã quy định.

Các phương án:

- **Dừng theo hoạt động tài khoản:** dừng ứng dụng sau 7 ngày người dùng không đăng nhập hoặc triển khai ứng dụng.
- **Dừng theo hoạt động ứng dụng:** dừng ứng dụng khi không nhận request trong một khoảng thời gian.
- **Sleep/Wake:** phát triển sau để ứng dụng tự khởi động khi có request mới.

Traefik chỉ đảm nhiệm định tuyến request, không tự quản lý vòng đời container. Vì vậy, hệ thống cần một scheduled job hoặc runtime manager để kiểm tra và thực hiện việc dừng ứng dụng.

**Phương án ban đầu:** triển khai scheduled job để dừng ứng dụng Free sau 7 ngày không hoạt động. Cơ chế sleep/wake sẽ được xem xét ở giai đoạn tiếp theo.

Cần xác định rõ hoạt động nào làm mới thời gian không hoạt động và chính sách này áp dụng theo tài khoản hay từng ứng dụng.

### 2.2. Bán sự tiện lợi, không khóa chức năng cốt lõi

Gói Free vẫn phải cho phép người dùng triển khai và kiểm thử ứng dụng thực tế.

Các tính năng cơ bản như deploy từ ZIP hoặc repository public vẫn được hỗ trợ.

Các tính năng trả phí tập trung vào sự tiện lợi:

- Kết nối repository private.
- Tự động deploy khi có push code.
- Chạy ứng dụng liên tục.
- Sử dụng domain riêng.
- Hạn mức tài nguyên và số lượng project cao hơn.

### 2.3. Hạn chế lạm dụng tài nguyên

Các biện pháp bảo vệ:

- Yêu cầu xác thực email.
- Giới hạn CPU và RAM cho từng container.
- Giới hạn thời gian build và số build đồng thời.
- Theo dõi mức sử dụng CPU và bộ nhớ.
- Cảnh báo hoặc dừng ứng dụng vi phạm chính sách.
- Giới hạn project, service, dung lượng lưu trữ và băng thông.

CPU cao trong thời gian ngắn không đồng nghĩa với hành vi đào coin. Hệ thống cần xét mức sử dụng, thời lượng và các dấu hiệu bất thường trước khi xử lý.

### 2.4. Hạ gói không tự động xóa dữ liệu

Khi người dùng hạ gói:

- Giữ nguyên dữ liệu và source code hiện có.
- Không cho tạo mới tài nguyên nếu đang vượt hạn mức.
- Cho phép người dùng xem và xóa tài nguyên.
- Áp dụng chính sách runtime phù hợp với gói mới.
- Cho phép tạo mới trở lại khi hạn mức được đáp ứng.

Giữ dữ liệu không có nghĩa là mọi container vượt hạn mức đều tiếp tục chạy. Trạng thái runtime phải tuân thủ chính sách gói hiện tại và được thông báo rõ ràng cho người dùng.

## 3. Các gói dịch vụ

Giá bán dưới đây là mức giá đề xuất ban đầu cho thị trường Việt Nam. Các hạn mức cần được kiểm chứng bằng chi phí hạ tầng thực tế trước khi đưa vào vận hành chính thức.

| Tính năng | Free | Starter | Pro |
|---|---:|---:|---:|
| Giá mỗi tháng | 0đ | 299.000đ | 749.000đ |
| Số project | 1 | 3 | 10 |
| Số service | 1 | 2 | 5 |
| RAM mỗi service | 256 MB | 1 GB | 2 GB |
| Giới hạn CPU mỗi service | 0,5 core | 0,5 core* | 0,5 core* |
| Chạy liên tục | Không | Có | Có |
| Deploy từ ZIP | Có | Có | Có |
| Deploy repository public | Có | Có | Có |
| Deploy repository private | Không | Không | Có |
| Tự động deploy khi push code | Không | Có | Có |
| Thời gian build mỗi tháng | 100 phút | 500 phút | 2.000 phút |
| Thời gian tối đa mỗi build | 5 phút | 10 phút | 15 phút |
| Dung lượng lưu trữ | 1 GB | 10 GB | 30 GB |
| Băng thông mỗi tháng | 5 GB | 50 GB | 150 GB |
| Số bản deploy được giữ | 2 | 5 | 10 |
| Custom domain | Không | Không | Có |

\* Giới hạn CPU của các gói trả phí là cấu hình khởi đầu, có thể điều chỉnh theo năng lực hạ tầng và mô hình chi phí.

### 3.1. Gói Free — 0đ/tháng

Dành cho người học, thử nghiệm và các dự án nhỏ.

- Hỗ trợ deploy từ ZIP và repository public.
- RAM tối đa 256 MB mỗi service.
- Giới hạn CPU 0,5 core mỗi service.
- Không đảm bảo ứng dụng chạy liên tục.
- Tự động dừng ứng dụng theo chính sách không hoạt động.
- Giới hạn dung lượng, băng thông và thời gian build.
- Không hỗ trợ custom domain.

### 3.2. Gói Starter — 299.000đ/tháng

Dành cho ứng dụng cá nhân và dự án quy mô nhỏ.

- Tối đa 3 project và 2 service.
- RAM tối đa 1 GB mỗi service.
- Hỗ trợ chạy liên tục.
- Hỗ trợ deploy từ ZIP và repository public.
- Hỗ trợ tự động deploy từ repository được tích hợp.
- Dung lượng lưu trữ 10 GB.
- Băng thông 50 GB mỗi tháng.

### 3.3. Gói Pro — 749.000đ/tháng

Dành cho dự án cần nhiều tài nguyên và khả năng triển khai thuận tiện hơn.

- Tối đa 10 project và 5 service.
- RAM tối đa 2 GB mỗi service.
- Hỗ trợ chạy liên tục.
- Hỗ trợ repository private.
- Hỗ trợ tự động deploy khi push code.
- Hỗ trợ custom domain.
- Dung lượng lưu trữ 30 GB.
- Băng thông 150 GB mỗi tháng.

## 4. Quản lý tài nguyên

### 4.1. Tách tài nguyên build và runtime

Build ứng dụng và chạy ứng dụng là hai loại workload khác nhau.

**Giai đoạn build**

- Cài đặt dependency.
- Biên dịch source code.
- Tạo file tĩnh hoặc container image.
- Tạo artifact để triển khai.

Build job cần giới hạn CPU, RAM, thời gian thực thi và số lượng tác vụ đồng thời. Sau khi build hoàn tất, tài nguyên tạm thời phải được giải phóng.

**Giai đoạn runtime**

- Chạy backend và các ứng dụng cần xử lý phía server.
- Định tuyến request thông qua Traefik.
- Theo dõi CPU và RAM.
- Quản lý trạng thái container.

Ứng dụng React + Vite sau khi chạy `npm run build` có thể được phục vụ bằng static hosting hoặc object storage kết hợp CDN. Không nhất thiết phải duy trì một container Node.js chạy liên tục.

Các ứng dụng Node.js API, Django API hoặc Next.js SSR thường cần tài nguyên runtime riêng.

### 4.2. Giới hạn CPU và RAM

Mỗi container phải được áp dụng giới hạn tài nguyên ở cấp container hoặc hệ điều hành.

Cấu hình Free ban đầu:

- CPU: 0,5 core.
- RAM: 256 MB.

Đây là hạn mức thử nghiệm, không phải cam kết rằng mọi ứng dụng đều chạy được trong cấu hình này.

Hệ thống cần thu thập:

- CPU đang sử dụng và mức cao nhất.
- RAM đang sử dụng và mức cao nhất.
- Trạng thái thoát của container.
- Sự kiện hết bộ nhớ (OOM).
- Số lần restart.
- Hoạt động request nếu có.

### 4.3. Dung lượng lưu trữ và băng thông

Hai loại hạn mức phải được quản lý riêng biệt.

- **Dung lượng lưu trữ:** lượng dữ liệu được giữ lại, chẳng hạn source code, file ứng dụng và dữ liệu persistent.
- **Băng thông:** lượng dữ liệu được truyền đi trong một kỳ tính phí.

Cần xác định rõ loại dữ liệu nào được tính vào quota lưu trữ và cách đo băng thông.

Khi vượt băng thông, hệ thống phải thực thi chính sách đã công bố, chẳng hạn giới hạn traffic, yêu cầu nâng cấp hoặc cung cấp gói băng thông bổ sung. Không được phát sinh khoản phí ngoài dự kiến mà người dùng chưa được thông báo.

## 5. Vòng đời ứng dụng Free

### 5.1. Theo dõi hoạt động

Các mốc thời gian có thể được lưu trong hệ thống:

- `last_login_at`: lần đăng nhập gần nhất.
- `last_deployed_at`: lần triển khai gần nhất.
- `last_request_at`: lần nhận request gần nhất, nếu hỗ trợ theo dõi request.

Đối với phiên bản đầu tiên, có thể sử dụng thời điểm đăng nhập hoặc triển khai thành công để xác định hoạt động của tài khoản.

### 5.2. Scheduled job tự động dừng ứng dụng

Một background job định kỳ thực hiện:

1. Tìm các ứng dụng thuộc gói Free.
2. Kiểm tra thời điểm hoạt động gần nhất.
3. Xác nhận ứng dụng không trong quá trình build hoặc deploy.
4. Đánh dấu ứng dụng đang dừng.
5. Gọi runtime manager để dừng container.
6. Lưu lý do và thời điểm dừng.
7. Cập nhật trạng thái ứng dụng.

Job phải có tính idempotent, tránh xử lý trùng gây sai lệch trạng thái.

### 5.3. Trạng thái runtime

Các trạng thái đề xuất:

- `BUILDING`: đang build.
- `DEPLOYING`: đang triển khai.
- `RUNNING`: đang chạy.
- `STOPPING`: đang dừng.
- `STOPPED`: đã dừng.
- `FAILED`: thất bại.

Cần định nghĩa các chuyển đổi trạng thái hợp lệ để tránh xung đột giữa các thao tác deploy, start và stop.

### 5.4. Khởi động lại ứng dụng

Khi người dùng yêu cầu khởi động một ứng dụng đã dừng:

1. Kiểm tra quyền sở hữu project.
2. Kiểm tra gói và hạn mức tài nguyên.
3. Xác nhận ứng dụng đủ điều kiện chạy.
4. Khởi động container.
5. Cập nhật trạng thái và thời điểm hoạt động.
6. Trả về URL và trạng thái ứng dụng.

Cơ chế tự đánh thức khi có request sẽ được phát triển sau. Cơ chế này cần xử lý độ trễ khởi động, timeout và các request đến trong lúc container chưa sẵn sàng.

## 6. Chính sách hạ gói

Khi người dùng chuyển sang gói thấp hơn:

1. Cập nhật gói dịch vụ và hạn mức có hiệu lực.
2. Tính lại mức sử dụng tài nguyên.
3. Giữ nguyên metadata, source code và dữ liệu đã lưu.
4. Chặn tạo mới tài nguyên khi vượt hạn mức.
5. Áp dụng chính sách runtime đã công bố.
6. Thông báo cho người dùng về hạn mức vượt quá và cách khắc phục.

**Ví dụ:** người dùng có 5 project thuộc gói Pro và chuyển xuống Free, chỉ cho phép 1 project.

Hệ thống giữ nguyên cả 5 project và dữ liệu. Người dùng không thể tạo thêm project mới cho đến khi số lượng nằm trong giới hạn. Họ có thể xóa project hoặc nâng cấp trở lại.

Việc các project vượt hạn mức có tiếp tục chạy hay không phải được quyết định theo chính sách runtime, không được để hành vi này phụ thuộc ngẫu nhiên vào cơ sở dữ liệu.

## 7. Chống lạm dụng và bảo mật

Các biện pháp cần triển khai:

- Xác thực email trước khi cho phép deploy.
- Kiểm tra xác thực và quyền truy cập trên backend.
- Giới hạn CPU và RAM ở cấp container.
- Timeout cho build job.
- Giới hạn build và deploy đồng thời.
- Theo dõi mức sử dụng tài nguyên bất thường.
- Rate limit cho các API nhạy cảm.
- Ghi log các sự kiện tự động dừng, deploy thất bại và vi phạm quota.

Khi phát hiện dấu hiệu bất thường, hệ thống có thể cảnh báo, tạm ngưng ứng dụng hoặc yêu cầu kiểm tra thủ công theo chính sách đã công bố.

## 8. Thiết kế database và service

Schema thực tế cần tuân theo database hiện có. Các trường dưới đây là gợi ý khái niệm, không bắt buộc tạo mới nếu đã có trường tương đương.

**Subscription / Plan**

- Mã gói và trạng thái đăng ký.
- Hạn mức tài nguyên.
- Danh sách tính năng được cấp quyền.
- Kỳ thanh toán và trạng thái subscription.

**Project / Service**

- Chủ sở hữu và mã project.
- Deployment hiện tại và trạng thái runtime.
- Thời điểm hoạt động và triển khai gần nhất.
- Giới hạn tài nguyên.
- Lý do và thời điểm dừng, nếu cần.

**Runtime Manager**

- Khởi động và dừng container.
- Áp dụng giới hạn CPU và RAM.
- Kiểm tra tình trạng container.
- Ngăn các thao tác vòng đời xung đột.

**Quota Enforcement**

- Kiểm tra quota khi tạo tài nguyên hoặc deploy.
- Thực thi giới hạn ở backend.
- Tính lại mức sử dụng sau khi đổi gói.

**Scheduled Jobs**

- Dừng ứng dụng Free không hoạt động.
- Dọn dẹp artifact build tạm thời hết hạn.
- Đối soát trạng thái database với trạng thái container thực tế.

## 9. Lộ trình triển khai

### Giai đoạn 1 — Quản lý hạn mức

- Xây dựng cấu hình gói và danh sách quyền lợi.
- Kiểm tra quota project, service, CPU, RAM, storage và build.
- Tách tài nguyên build khỏi runtime.
- Giữ dữ liệu khi hạ gói.

### Giai đoạn 2 — Quản lý vòng đời runtime

- Triển khai start/stop container.
- Quản lý trạng thái và audit log.
- Xây dựng scheduled job dừng ứng dụng Free.
- Đảm bảo thao tác an toàn khi có nhiều request đồng thời.

### Giai đoạn 3 — Giám sát và chống lạm dụng

- Thu thập CPU và RAM.
- Phát hiện OOM và lỗi lặp lại.
- Xây dựng chính sách xử lý CPU cao kéo dài.
- Bổ sung thông báo người dùng và cảnh báo vận hành.

### Giai đoạn 4 — Tính năng nâng cao

- Hỗ trợ repository private theo quyền lợi gói.
- Triển khai tự động khi push code.
- Quản lý custom domain.
- Xem xét sleep/wake và các gói băng thông bổ sung.

## 10. Nguyên tắc bắt buộc

1. Ứng dụng Free không được tiêu thụ tài nguyên runtime vô thời hạn nếu không có chính sách rõ ràng.
2. Các chức năng deploy cốt lõi vẫn có trên Free trong giới hạn cho phép.
3. Quota phải được thực thi ở backend và hạ tầng runtime.
4. Tài nguyên build và runtime phải được đo lường riêng biệt.
5. Hạ gói không tự động xóa dữ liệu người dùng.
6. Phải xác định rõ cách xử lý runtime khi vượt hạn mức.
7. Tự động dừng và khởi động phải an toàn khi có deploy hoặc request đồng thời.
8. Giá bán và hạn mức cần được kiểm chứng với chi phí hạ tầng thực tế trước khi phát hành chính thức.
