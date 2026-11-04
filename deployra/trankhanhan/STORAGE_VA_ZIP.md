File 1

# Tổng hợp: kho lưu zip và giải nén zip

Hai class này lo đường đi của file zip người dùng upload: **cất vào kho** rồi **mở ra thành thư mục mã nguồn** để build.

```
upload zip → LocalSourceStorage.put → (lưu object_key vào source_uploads)
deploy     → LocalSourceStorage.open → ZipSourceExtractor.extract → thư mục tạm → build image
```

Hiện tại chưa có code nào gọi hai class này. API upload và `DeploymentService` sẽ là nơi gọi.

## 1. `storage/LocalSourceStorage`

Cài đặt `SourceStorage`, lưu file trên ổ đĩa máy chủ, trong thư mục `deployra.storage.local-dir` (mặc định `./data/uploads`). DB chỉ lưu `object_key` tương đối (ví dụ `uploads/42/abc.zip`) và `storage_backend=LOCAL`; đường dẫn đầy đủ được ghép lúc chạy. Sau này chuyển sang S3/R2 chỉ cần thêm một class cài đặt khác.

| Hàm | Việc làm |
|---|---|
| `backend()` | Trả về `LOCAL` |
| `put(key, data, sizeBytes)` | Ghi vào file tạm cùng thư mục rồi đổi tên nguyên tử, nên không bao giờ để lại file ghi dở dưới đúng key. Nếu `sizeBytes >= 0` thì số byte nhận được phải khớp, nếu không thì bỏ file. Ghi đè key cũ được. |
| `open(key)` | Mở luồng đọc; ném `StorageException` nếu file không có |
| `delete(key)` | Xoá file; gọi lần hai không lỗi |

Key chỉ nhận ký tự `A-Za-z0-9._/-`, bắt đầu bằng chữ hoặc số, dài tối đa 300; chặn `..`, `//`, `/./`, dấu `/` ở cuối, và mọi đường dẫn thoát khỏi thư mục gốc.

### Test (9) — `LocalSourceStorageTest`

| Test | Kiểm tra |
|---|---|
| `backendIsLocal` | `backend()` trả `LOCAL` |
| `putThenOpenReturnsSameBytes` | Ghi rồi đọc lại ra đúng nội dung (có tiếng Việt), file nằm đúng thư mục con |
| `putAgainReplacesTheFile` | Ghi lại cùng key thì thay nội dung |
| `unknownSizeIsAccepted` | `sizeBytes = -1` (không biết trước) vẫn ghi được |
| `sizeMismatchIsRejectedAndLeavesNothingBehind` | Sai dung lượng khai báo thì ném lỗi, không để file dở và không sót file tạm |
| `failedOverwriteKeepsOldContent` | Ghi đè thất bại thì nội dung cũ còn nguyên |
| `deleteRemovesFileAndIsIdempotent` | Xoá được, xoá lần hai không lỗi |
| `openMissingFileThrows` | Mở file không tồn tại thì ném `StorageException` |
| `rejectsKeysThatCouldEscapeTheStorageRoot` | 12 key xấu (`../x`, `/etc/passwd`, `a//b`, khoảng trắng, `\`, NUL, `..`, `.hidden`…) bị chặn ở cả `put`, `open`, `delete`; key `null` bị chặn; không có file nào bị tạo ngoài thư mục gốc |

## 2. `deploy/ZipSourceExtractor`

Tạo bằng `new ZipSourceExtractor(maxEntries, maxFileBytes, maxTotalBytes)` (bean cấu hình trong `DeployConfig`, đọc từ `deployra.zip.*`). Hàm chính:

`extract(InputStream zip, Path destination)` trả về `Result(directory, fileCount, totalBytes)`.

Cách xử lý:
- **Chống zip-slip:** chặn đường dẫn tuyệt đối, `..`, ký tự ổ đĩa kiểu `C:`, NUL; đổi `\` của Windows thành `/`.
- **Chống zip bomb:** đếm số byte thực sự giải nén ra (không tin kích thước khai báo trong zip), giới hạn từng file, tổng dung lượng và số entry.
- **Chống rối cấu trúc:** giới hạn độ sâu 40, độ dài tên, từ chối tên trùng nhau chỉ khác hoa/thường, từ chối file trùng tên với thư mục.
- **Dọn rác:** bỏ `__MACOSX`, `.DS_Store`, `Thumbs.db`.
- **Tự bỏ thư mục gốc duy nhất** (kiểu zip tải từ GitHub: `repo-main/…` thành `…`).
- **Thư mục đích phải rỗng hoặc chưa có**; nếu giải nén lỗi thì dọn sạch phần đã ghi.
- Zip không có file nào, hoặc chỉ có thư mục, thì báo lỗi.

Hạn chế: `ZipInputStream` của Java không đọc được quyền Unix nên mất bit thực thi của file.

### Test (20) — `ZipSourceExtractorTest`

| Nhóm | Test | Kiểm tra |
|---|---|---|
| Giải nén đúng | `extractsFilesAndNestedFolders` | File và thư mục lồng nhau ra đúng, số file và tổng byte đúng |
| | `stripsSingleTopLevelFolderLikeGithubZips` | Bỏ thư mục gốc duy nhất |
| | `stripsRootEvenWhenChildHasSameName` | Bỏ thư mục gốc kể cả khi bên trong có thư mục trùng tên |
| | `keepsLayoutWhenThereAreSeveralTopLevelEntries` | Có nhiều mục ở gốc thì giữ nguyên |
| | `keepsSingleFileAtRoot` | Một file ở gốc thì giữ nguyên |
| | `ignoresMacAndWindowsJunk` | Bỏ `__MACOSX`, `.DS_Store`, `Thumbs.db` |
| | `acceptsBackslashSeparatorsFromWindowsTools` | Zip tạo bằng công cụ Windows (dùng `\`) vẫn đọc được |
| Bảo mật đường dẫn | `rejectsZipSlipAndAbsolutePaths` | Chặn `../`, đường dẫn tuyệt đối, ổ đĩa |
| | `rejectsNulInName` | Chặn tên chứa NUL |
| | `rejectsNamesThatDifferOnlyByCase` | Chặn tên chỉ khác hoa/thường |
| | `rejectsFileAndFolderWithSameName` | Chặn file trùng tên thư mục |
| | `rejectsTooDeepNesting` | Chặn lồng quá sâu |
| Giới hạn | `rejectsSingleFileThatInflatesTooLarge` | Một file phình quá giới hạn |
| | `rejectsWhenTotalUnpackedSizeExceedsLimit` | Tổng dung lượng vượt giới hạn |
| | `rejectsTooManyEntries` | Quá nhiều entry |
| Dữ liệu hỏng | `rejectsEmptyZip` | Zip rỗng |
| | `rejectsZipWithOnlyFolders` | Zip chỉ có thư mục |
| | `rejectsNonZipData` | Dữ liệu không phải zip |
| | `rejectsTruncatedZip` | Zip bị cắt cụt |
| Thư mục đích | `refusesNonEmptyDestinationAndKeepsExistingFiles` | Đích không rỗng thì từ chối và giữ nguyên file cũ |

Các test lỗi còn kiểm tra thêm là thư mục đích được dọn sạch sau khi thất bại.

## 3. Trạng thái kiểm chứng

- 29 test này (9 storage + 20 zip) đã chạy thật và qua hết, bằng bộ giả lập JUnit trong môi trường của mình, vì môi trường đó không tải được Maven.
- Chưa chạy bằng `./mvnw verify`. Khi chạy lần đầu trên máy bạn, JUnit thật có thể báo khác biệt nhỏ; hãy gửi lỗi nếu có.

## 4. Chưa làm

- API upload zip và việc tạo dòng `source_uploads` (Người 3 + `EntitlementService.requireCanUpload`).
- Job dọn zip hết hạn và zip mồ côi. Hiện `SourceUploadRepository` mới có truy vấn zip hết hạn; `source_uploads` chưa có `service_id`, nên chưa biết zip mồ côi thuộc service nào.
- `S3SourceStorage`.