File thứ 2

# Tổng hợp thay đổi – Deployra-System (nhánh `TranKhanhAn`)

Tài liệu này tóm tắt toàn bộ phần đã đọc, đã sửa và đã kiểm chứng trong repo
`TKAn08/Deployra-System`, nhánh `TranKhanhAn`. Đường dẫn code chính tính từ
`deployra/src/main/java/com/paas/deployra/`, đường dẫn test tính từ
`deployra/src/test/java/com/paas/deployra/`.

---

## 1. Hiện trạng ban đầu

GitHub chặn công cụ đọc web nên repo được đọc bằng cách `git clone` nhánh
`TranKhanhAn`. Kết quả đọc phần API upload zip:

- `POST /api/services/{serviceId}/uploads` gọi `SourceUploadService.uploadZip()`.
- Hàm này kiểm tra file, hạn mức, lưu file, ghi bảng `source_uploads`, rồi **gọi
  luôn `deployments.enqueue(...)`** ở cuối.
- Controller trả `201` kèm `deploymentId` và `deploymentStatus`.
- `enqueue` chỉ tạo deployment `QUEUED` và đẩy vào hàng đợi. Các worker nền
  (`deploy-worker-N`) mới thực sự build và run.

Kết luận: **upload zip kích hoạt deploy luôn** (một luồng), nhưng phần build/run
chạy bất đồng bộ phía sau.

## 2. Yêu cầu và hướng xử lý

| Yêu cầu | Hướng xử lý |
|---|---|
| Tách upload và run thành 2 luồng như Railway | Upload chỉ lưu file; thêm endpoint riêng để bấm deploy |
| Có thêm luồng GitHub | Dùng chung endpoint deploy, không có `uploadId` thì chạy từ `repoUrl`/`branch` của service |
| Env: chỉ lấy key, không lấy value (cả `.env` và `.env.example`) | Sửa `DotEnvParser` và `EnvFileImporter` |
| Bỏ qua đăng nhập, chạy anonymous trước | Giữ `userId = null` trong controller (xem mục 7) |

---

## 3. Luồng mới

```
Luồng A: ZIP
  POST /api/services/{id}/uploads        -> 201 {uploadId, sizeBytes, sha256, expiresAt}   (chỉ lưu)
  POST /api/services/{id}/deployments    -> body {"uploadId": 12} -> 202 QUEUED

Luồng B: GITHUB
  POST /api/services/{id}/deployments    -> không body (hoặc {}) -> 202 QUEUED
                                            dùng repoUrl + branch của service

Sau đó (chạy nền, hai luồng dùng chung, không đổi):
  QUEUED -> worker lấy ra -> kiểm hạn mức build -> lấy source (giải nén zip / git clone)
         -> kiểm tra Dockerfile -> import .env -> build (BUILDING) -> start container (DEPLOYING)
         -> health check -> LIVE (xóa container cũ) | FAILED | CANCELLED
```

Ví dụ gọi:

```bash
# 1. Upload (chỉ lưu)
curl -F file=@app.zip localhost:8080/api/services/1/uploads
# -> 201 {"uploadId": 12, "sizeBytes": ..., "sha256": "...", "expiresAt": "..."}

# 2a. Chạy từ zip vừa upload
curl -X POST -H 'Content-Type: application/json' -d '{"uploadId":12}' \
     localhost:8080/api/services/1/deployments
# -> 202 {"deploymentId": 30, "status": "QUEUED", "sourceType": "ZIP"}

# 2b. Chạy từ GitHub của service
curl -X POST localhost:8080/api/services/1/deployments
# -> 202 {"deploymentId": 31, "status": "QUEUED", "sourceType": "GIT"}
```

---

## 4. Danh sách file thay đổi

### Code chính

| File | Thay đổi |
|---|---|
| `paas/SourceUploadRepository.java` | Thêm `findByIdAndUserIdAndDeletedAtIsNull(id, userId)` |
| `paas/SourceUploadService.java` | `uploadZip` chỉ lưu và trả `SourceUpload` (bỏ `record Result` và đoạn `enqueue`). Thêm `deployUpload`, `deployFromGit`, `requireOwnedAppService` |
| `api/UploadController.java` | Response đổi thành `{uploadId, sizeBytes, sha256, expiresAt}`, không còn thông tin deployment |
| `api/DeploymentController.java` | **File mới**: `POST /api/services/{serviceId}/deployments`, trả `202 Accepted` |
| `deploy/DeploymentService.java` | Fix bug: set `sourceType` (+1 dòng trong `doEnqueue`) |
| `common/ApiExceptionHandler.java` | Thêm handler `InvalidRequestException` -> 400, `NotFoundException` -> 404 |
| `common/DotEnvParser.java` | Chỉ trả tên biến (xem mục 6) |
| `deploy/EnvFileImporter.java` | Chỉ tạo biến với giá trị rỗng (xem mục 6) |

### Test

| File | Thay đổi |
|---|---|
| `api/DeploymentControllerTest.java` | **Mới**, 6 test cho `POST /deployments` |
| `api/UploadControllerTest.java` | Chuyển thư mục, sửa theo response mới và controller hiện tại |
| `paas/SourceUploadServiceTest.java` | Chuyển thư mục, sửa test upload, bỏ 1 test, thêm 7 test deploy |
| `storage/LocalSourceStorageTest.java` | Chỉ chuyển thư mục, nội dung không đổi |
| `DeployraApplicationTests.java` | Thêm 3 `@MockitoBean` để context khởi động (tạm) |
| `common/DotEnvParserTest.java` | **Mới**, 10 test |
| `deploy/EnvFileImporterTest.java` | **Mới**, 7 test |

---

## 5. Chi tiết từng thay đổi

### 5.1. Tách upload và deploy (`SourceUploadService`)

`uploadZip` giữ nguyên các bước kiểm tra (service thuộc user, đúng loại APP, đuôi
`.zip`, file không rỗng, hạn mức, ghi kho, SHA-256), chỉ bỏ đoạn gọi `enqueue`.

Hai hàm mới:

- `deployUpload(userId, serviceId, uploadId)`: kiểm tra service, kiểm tra upload
  **thuộc đúng user** và chưa xoá, chưa hết hạn, rồi `enqueue(service, MANUAL, upload)`.
  Việc kiểm tra quyền sở hữu upload ngăn user A deploy zip của user B bằng cách đoán id.
- `deployFromGit(userId, serviceId)`: kiểm tra service có `repoUrl`, rồi
  `enqueue(service, MANUAL)`.

`DeploymentService` và interface `DeploymentServiceInterface` **không phải đổi**,
vì `enqueue` đã tách sẵn hai dạng (có upload / không có upload).

### 5.2. Fix bug `sourceType`

`Deployment.sourceType` mặc định là `GIT`, nhưng `doEnqueue` chỉ gọi
`d.setUpload(upload)` mà không set `sourceType`. Hậu quả: deploy từ zip vẫn lưu
`source_type = GIT`. Pipeline vẫn chạy đúng vì nó rẽ nhánh theo `getUpload() != null`,
nên lỗi không lộ ra, nhưng dữ liệu bị sai.

```java
d.setUpload(upload);
d.setSourceType(upload != null ? Deployment.SourceType.ZIP : Deployment.SourceType.GIT);
```

### 5.3. `ApiExceptionHandler`

Javadoc của `InvalidRequestException` và `NotFoundException` ghi "tầng API đổi thành
HTTP 400/404", và các test cũ cũng kỳ vọng vậy, nhưng handler chỉ xử lý
`QuotaExceededException`. Nếu không có handler thì API trả 500. Đã thêm:

- `InvalidRequestException` -> 400 `{"error": "invalid_request", "message": ...}`
- `NotFoundException` -> 404 `{"error": "not_found", "message": ...}`

---

## 6. Env: chỉ lấy key, không lấy value

**`DotEnvParser`**
- `Result` đổi từ `values` (Map key -> value) thành `keys` (`Set<String>`, giữ thứ tự, loại trùng).
- Parser vẫn phải đi qua phần value để biết value kết thúc ở đâu. Nếu không, các dòng
  bên trong value nhiều dòng (ví dụ private key có dấu `=`) sẽ bị nhận nhầm thành key.
  Nội dung value không được giữ lại, trả ra, lưu hay log.
- Bỏ hàm giải mã escape (không còn dùng). Giữ nguyên quy tắc bỏ qua dòng lỗi
  (thiếu `=`, tên biến sai, nháy không đóng, ký tự thừa sau nháy đóng).

**`EnvFileImporter`**
- Đọc cả `.env.example` và `.env`, gộp key của hai file.
- Biến chưa có trong DB thì tạo với giá trị rỗng (`EMPTY_VALUE = ""`). Biến đã có thì không đụng tới.
- Log chỉ ghi tên biến, kèm ghi chú "giá trị để trống, cần tự điền".
- Lưu chuỗi rỗng đã mã hoá thay vì null, vì cột `value_encrypted` đang `nullable = false`
  (không phải đổi schema).

---

## 7. Vấn đề phát hiện trong code có sẵn

1. **Test nằm sai thư mục.** `src/test/api`, `src/test/paas`, `src/test/storage` nằm ngoài
   `src/test/java` nên Maven không chạy. Đã chuyển vào `src/test/java/com/paas/deployra/...`
   (package vốn đã khớp).
2. **`UploadControllerTest` cũ lỗi thời**: dùng `CurrentUser` và `UnauthorizedException`
   không có trong nhánh. Đã sửa theo controller hiện tại (`userId = null`), bỏ test 401.
   Khi có đăng nhập cần viết lại test này.
3. **`DeployraApplicationTests.contextLoads` không thể chạy qua.** `DeploymentService` cần
   3 bean chưa tồn tại: `ContainerRuntime` (chỉ có interface), `GitSourceFetcher` và
   `ZipSourceExtractor` (không có annotation nào để Spring tạo bean). Đã thêm
   `@MockitoBean` cho cả ba kèm TODO. Đây chỉ là giải pháp tạm.
4. **Chạy anonymous chưa hoàn chỉnh.** Controller đang truyền `userId = null`, mà các
   truy vấn kiểm tra chủ sở hữu (`findByIdAndProjectOwnerIdAndDeletedAtIsNull(serviceId, null)`)
   sẽ không tìm thấy service nào. Để chạy anonymous thật cần một cách tạm (ví dụ cố định
   một owner id khi dev). Phần này **chưa làm**. `SecurityConfig` cũng chưa được đọc/kiểm tra,
   và dự án có `spring-boot-starter-security` nên có thể chặn request.

---

## 8. Trạng thái công việc

| Hạng mục | Trạng thái |
|---|---|
| Upload zip (kiểm tra, hạn mức, lưu kho, ghi DB) | Có sẵn từ trước, đã bỏ đoạn tự deploy |
| `POST /deployments` (zip hoặc git) | Đã viết, nằm trong patch |
| Hàng đợi, worker, cancel deploy cũ, khôi phục khi restart | Có sẵn |
| Pipeline lấy source, kiểm tra Dockerfile, import `.env` | Có sẵn, chưa chạy thật |
| Fix `sourceType`, handler 400/404 | Đã làm |
| Env chỉ lấy key | Đã làm |
| Chuyển và sửa test, thêm test mới | Đã làm |
| **`ContainerRuntime` (build image, start/stop container)** | **Chưa có**, mới có interface |
| Khai báo bean `GitSourceFetcher`, `ZipSourceExtractor` | Chưa có |
| Chạy anonymous (mục 7.4) | Chưa làm |
| Đăng nhập, `userId` thật | Bỏ qua theo yêu cầu, để sau |
| Endpoint xem trạng thái và log deployment | Chưa có |
| API để người dùng điền giá trị cho biến env | Chưa có (mới có `EnvVariableService.set`) |
| Job dọn zip hết hạn | Chưa có |
| Repo GitHub private (token) | Chưa có |
| Webhook GitHub tự deploy | Chưa có (mới có giá trị `WEBHOOK` trong enum) |

Hiện gọi `POST /deployments` sẽ tạo deployment `QUEUED` nhưng chưa thể chạy ra container
thật vì thiếu `ContainerRuntime`.

---

## 9. Cần quyết định

- **Biến env giá trị rỗng khi start container.** `DeploymentService` (khoảng dòng 265)
  truyền toàn bộ biến vào container, nên biến chưa điền sẽ thành `KEY=""`. Nhiều ứng dụng
  xử lý chuỗi rỗng khác với biến không tồn tại (ví dụ Spring `${X:mặc định}` với `X=""` ra
  rỗng chứ không dùng mặc định). Đề xuất: bỏ qua biến có giá trị rỗng khi start container.
  Chưa sửa vì ngoài phạm vi yêu cầu.
- **Handler 400/404.** Nếu một bạn khác trong nhóm đã làm handler này ở nhánh khác thì
  merge có thể xung đột; giữ bản đầy đủ hơn.
- **Tên hàm.** `deployUpload`/`deployFromGit` đang nằm trong `SourceUploadService`. Nếu thấy
  lệch tên có thể tách sang một service riêng.

---

## 10. Mức độ kiểm chứng

- **Đã chạy thật:** `DotEnvParser` và `EnvFileImporter` (importer chạy với stub tối thiểu cho
  Spring/DB) cho tất cả tình huống trong test tương ứng: key giá trị rỗng, value nhiều dòng
  không bị nhận nhầm key, dòng lỗi bị bỏ qua, DB và log không chứa secret.
- **Đã kiểm tra cú pháp:** parse cả 15 file Java đã sửa bằng API của JDK, 0 lỗi cú pháp.
- **Chưa làm được:** compile cả project và chạy các test JUnit/Mockito, vì môi trường làm
  việc không tải được thư viện Maven. **Cần chạy `./mvnw test` trên máy của bạn.**
- **Lưu ý:** hai lần đầu có báo "đã soát cú pháp bằng javac" nhưng lúc đó máy chưa có
  `javac` nên lệnh không chạy thật. Lần kiểm tra ở trên (sau khi cài JDK) mới là kiểm tra thật.
- Có thể phát sinh lỗi khi compile thật (ví dụ chữ ký hàm Spring Boot 4), nếu gặp thì gửi
  log để sửa.

---

## 11. Cách áp dụng

Có hai cách, cùng nội dung:

1. **Patch** (đã kiểm tra apply sạch lên bản clone mới của nhánh `TranKhanhAn`), chạy ở thư
   mục gốc repo:
   ```bash
   git apply tach-upload-va-deploy.patch
   ```
   Patch xử lý đúng việc chuyển file test sang thư mục mới.
2. **Zip** `deployra-changes.zip`: giải nén đè vào repo. Zip không thể biểu diễn việc xoá
   file, nên sau khi đè cần **xoá thủ công 3 file test ở chỗ cũ**:
   `src/test/api/UploadControllerTest.java`, `src/test/paas/SourceUploadServiceTest.java`,
   `src/test/storage/LocalSourceStorageTest.java`
   (các thư mục này sẽ trống sau khi chuyển).

Sau khi áp dụng: `./mvnw test`.