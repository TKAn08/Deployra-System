File 3

Luồng GitHub được tách thành các bước riêng. Bước "kiểm tra bằng clone thật" đã bỏ theo yêu cầu, nên chỉ còn lưu repo, lưu env rồi chạy. Hai API không tạo class service mới, mà dùng lại class đã có cho đúng chỗ:

API	Controller	Logic nằm ở
PUT /api/services/{serviceId}/repo	UploadController (dùng chung với upload zip, cùng là nguồn mã của service)	SourceUploadService.setRepo
PUT /api/services/{serviceId}/env	EnvVariableController (mới, vì chưa có controller env nào)	EnvVariableService.setForUser (class vốn dùng để ghi biến env, EnvFileImporter cũng gọi nó)

PUT /repo: body {"repoUrl": "...", "branch": "main"}

repoUrl bắt buộc; branch bỏ trống nghĩa là dùng branch mặc định của repo (lưu null).
Dùng lại bộ kiểm tra của GitSourceFetcher mà lúc deploy cũng dùng: chỉ https, không chứa user/mật khẩu, không có ?/#, chỉ cổng 443, chặn localhost và địa chỉ nội bộ (có phân giải DNS). Vì vậy request này có một lần phân giải DNS. validateRepoUrl và requireBranch được mở sang public.
Gọi lại sẽ thay thế cấu hình cũ (kể cả branch). Chỉ nhận service loại APP.
Chỉ lưu, không clone, không chạy. Lỗi kiểm tra trả 400, không tìm thấy service trả 404.

PUT /env: body {"key": "DB_PASS", "value": "abc"}

Tạo mới (201) hoặc cập nhật (200); response {"key", "created"} và không bao giờ chứa value.
Dùng để điền giá trị cho biến đã được tạo sẵn từ .env lúc upload, hoặc thêm biến mới.
Kiểm tra: key đúng định dạng [A-Za-z_][A-Za-z0-9_]* và tối đa 255 ký tự; value không null (chuỗi rỗng được phép) và tối đa 64 KB. Value được mã hoá khi lưu như trước.
Một request lưu một biến; muốn lưu nhiều thì gọi lặp lại.
EnvVariableService.set (dùng nội bộ bởi importer) giữ nguyên, không kiểm tra quyền.