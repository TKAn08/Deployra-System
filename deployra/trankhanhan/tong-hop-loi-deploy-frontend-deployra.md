# Tổng hợp lỗi Deploy Frontend trên Deployra

## 1. Hiện tượng ban đầu

Quá trình build Docker image diễn ra thành công, nhưng khi Deployra chạy
container thì deployment không hoàn tất. Sau khoảng 60 giây, hệ thống
báo lỗi:

``` text
FAILED: Health check quá thời gian (60s)
```

Ban đầu, lỗi có vẻ giống vấn đề health check hoặc kết nối mạng. Tuy
nhiên, đây chỉ là biểu hiện bên ngoài; nguyên nhân thực sự nằm ở quá
trình khởi động Nginx bên trong container.

## 2. Nguyên nhân gốc rễ

Log của container cho thấy:

``` text
nginx: [emerg] chown("/var/cache/nginx/client_temp", 101) failed (1: Operation not permitted)
```

Có hai thành phần liên quan:

-   Docker container của Deployra được chạy với tùy chọn
    `--cap-drop ALL`.
-   Image `nginx:alpine` có các script khởi động có thể thực hiện thao
    tác `chown` đối với những thư mục runtime của Nginx.

`--cap-drop ALL` loại bỏ toàn bộ Linux capabilities khỏi container,
trong đó có `CAP_CHOWN` --- quyền cho phép thay đổi chủ sở hữu file hoặc
thư mục.

Vì vậy, khi script khởi động của image Nginx cố đổi chủ sở hữu thư mục
sang UID `101`, thao tác này bị từ chối với lỗi
`Operation not permitted`. Nginx không khởi động bình thường, khiến
container không thể trả lời health check.

## 3. Vì sao Deployra báo timeout?

Luồng xảy ra như sau:

1.  Deployra build Docker image thành công.
2.  Deployra tạo và khởi chạy container.
3.  Tiến trình khởi động Nginx cố thực hiện `chown`.
4.  Thao tác `chown` bị chặn bởi việc container không có capability
    `CAP_CHOWN`.
5.  Nginx khởi động thất bại hoặc container liên tục khởi động lại.
6.  `runtime.waitHealthy()` kiểm tra endpoint của ứng dụng nhưng không
    nhận được phản hồi thành công.
7.  Hết thời gian chờ 60 giây, Deployra báo
    `Health check quá thời gian`.

Do đó, health check timeout là **hậu quả**, không phải nguyên nhân gốc
rễ. Đây cũng không phải lỗi Docker build.

## 4. Những thay đổi đã thực hiện

### 4.1. Chuyển sang Nginx unprivileged

Thay image:

``` dockerfile
FROM nginx:alpine
```

bằng:

``` dockerfile
FROM nginxinc/nginx-unprivileged:alpine
```

Image `nginx-unprivileged` được thiết kế để chạy Nginx bằng user không
phải root, thường là UID `101`, thay vì phụ thuộc vào quy trình khởi
động cần quyền root như cấu hình mặc định của image trước đó.

Cách này phù hợp hơn với chính sách giới hạn quyền của Deployra.

### 4.2. Chuyển port từ 80 sang 8080

Nginx unprivileged mặc định sử dụng port `8080`. Vì vậy, cần đồng bộ
port ở các vị trí liên quan.

Trong cấu hình Nginx:

``` nginx
server {
    listen 8080;

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

Trong Dockerfile:

``` dockerfile
EXPOSE 8080
```

Trong code Java cập nhật port của service:

``` java
updateServicePort(service, 8080);
```

Cần đảm bảo port mà Nginx thực sự lắng nghe, port mà Deployra lưu cho
service và port được health check sử dụng là thống nhất.

### 4.3. Đảm bảo Nginx đọc được file frontend

Khi chạy bằng user không phải root, Nginx cần có quyền đọc các file
frontend được copy vào document root.

Với Dockerfile multi-stage, có thể chỉ định owner khi copy:

``` dockerfile
COPY --chown=101:101 --from=builder /app/dist/ /usr/share/nginx/html/
```

Ở đây:

-   `101:101` là UID và GID được dùng cho user Nginx trong image.
-   `/app/dist/` là thư mục build frontend ở stage trước.
-   `/usr/share/nginx/html/` là thư mục chứa nội dung website được Nginx
    phục vụ.

Việc này giúp các file được copy có owner phù hợp với user chạy Nginx.

### 4.4. Sử dụng health check endpoint phù hợp

Tạo file health check tĩnh, ví dụ `/healthz.html`, trong thư mục
frontend. Sau đó cấu hình Docker health check:

``` dockerfile
HEALTHCHECK --interval=5s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz.html || exit 1
```

Lệnh này dùng `wget` để kiểm tra Nginx có phản hồi HTTP thành công tại
port `8080` hay không, không cần cài thêm `curl`.

## 5. Các vị trí cần đồng bộ trong Deployra

  Thành phần                Cấu hình cần dùng
  ------------------------- ----------------------------------------
  Nginx image               `nginxinc/nginx-unprivileged:alpine`
  Nginx listen              `8080`
  Dockerfile EXPOSE         `8080`
  Service port trong Java   `8080`
  Health check URL          `http://127.0.0.1:8080/healthz.html`
  User chạy Nginx           User không phải root, thường UID `101`
  File frontend             Có quyền đọc phù hợp với user Nginx

Nếu chỉ đổi port trong Dockerfile mà không cập nhật Nginx hoặc
`service.port`, health check và định tuyến có thể tiếp tục sử dụng sai
port.

## 6. Kết luận

Nguyên nhân của lỗi là sự không tương thích giữa cách khởi động của
image `nginx:alpine` và chính sách giới hạn Linux capabilities mà
Deployra áp dụng cho container.

Deployra sử dụng `--cap-drop ALL`, trong khi quy trình khởi động của
image Nginx ban đầu cố thực hiện `chown`. Vì container không có
`CAP_CHOWN`, Nginx không thể khởi động; sau đó health check thất bại và
deployment hết thời gian chờ.

Giải pháp là chuyển sang `nginxinc/nginx-unprivileged:alpine`, chạy
Nginx bằng user không phải root, sử dụng port `8080`, cấp quyền đọc phù
hợp cho file frontend và đồng bộ cấu hình health check với port mới.

**Kết quả:** Sau khi áp dụng các thay đổi trên, frontend đã khởi chạy
thành công trên Deployra.
