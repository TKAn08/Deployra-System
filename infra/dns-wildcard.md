# Domain wildcard cho service của người dùng (Task 3.3)

Mục tiêu: mỗi service sau khi deploy có địa chỉ riêng:

```
https://{slug}.deployra.<domain>        ví dụ: https://web-demo-a1b2c3.deployra.example.com
```

Muốn vậy cần (1) bản ghi DNS wildcard trỏ về Elastic IP của EC2, và (2) chứng chỉ TLS wildcard do Traefik xin từ Let's Encrypt.

Thay `<domain>` bằng tên miền bạn sở hữu (ví dụ `example.com`).

## Bước 1. Elastic IP

Cấp Elastic IP rồi gắn vào instance, để IP không đổi khi máy khởi động lại:

```bash
aws ec2 allocate-address --domain vpc
aws ec2 associate-address --instance-id i-xxxx --allocation-id eipalloc-xxxx
```

Ghi lại địa chỉ, gọi là `ELASTIC_IP` bên dưới.

## Bước 2. Bản ghi DNS

| Tên | Loại | Giá trị | Dùng cho |
|---|---|---|---|
| `deployra.<domain>` | A | `ELASTIC_IP` | Domain gốc của hệ thống |
| `*.deployra.<domain>` | A | `ELASTIC_IP` | Mọi service `{slug}.deployra.<domain>` và `api.deployra.<domain>` |

Nếu DNS nằm ở Route53, lưu file `dns-change.json`:

```json
{
  "Comment": "Deployra: apex va wildcard",
  "Changes": [
    {
      "Action": "UPSERT",
      "ResourceRecordSet": {
        "Name": "deployra.<domain>",
        "Type": "A",
        "TTL": 300,
        "ResourceRecords": [{ "Value": "ELASTIC_IP" }]
      }
    },
    {
      "Action": "UPSERT",
      "ResourceRecordSet": {
        "Name": "*.deployra.<domain>",
        "Type": "A",
        "TTL": 300,
        "ResourceRecords": [{ "Value": "ELASTIC_IP" }]
      }
    }
  ]
}
```

```bash
aws route53 change-resource-record-sets \
  --hosted-zone-id HOSTED_ZONE_ID --change-batch file://dns-change.json
```

Nếu DNS ở nhà cung cấp khác (Cloudflare, GoDaddy, Mắt Bão...), tạo 2 bản ghi A theo bảng trên bằng giao diện của họ.

Kiểm tra sau vài phút (tên con nào cũng phải ra `ELASTIC_IP`):

```bash
nslookup abc.deployra.<domain>
nslookup api.deployra.<domain>
```

## Bước 3. TLS cho apex + wildcard (Traefik + Let's Encrypt)

`docker-compose.stack.yml` đã cấu hình sẵn Traefik xin một chứng chỉ cho `BASE_DOMAIN` và `*.BASE_DOMAIN`.
Bạn chỉ cần điền biến trong `.env` (copy từ `.env.stack.example`):

```
BASE_DOMAIN=deployra.<domain>
APP_DOMAIN=api.deployra.<domain>
ACME_EMAIL=email-that-cua-ban@example.com
AWS_REGION=ap-southeast-1
ACME_DNS_PROVIDER=route53
```

### Chọn kiểu xác thực: DNS-01 hay HTTP-01

| | DNS-01 (khuyên dùng) | HTTP-01 |
|---|---|---|
| Xin được chứng chỉ wildcard `*.deployra.<domain>` | **Có** | **Không** |
| Cần gì | Quyền sửa DNS (Route53 qua IAM Role, xem `iam-role.md`) | Cổng 80 mở ra internet |
| Số chứng chỉ | 1 chứng chỉ phủ mọi service | Mỗi tên miền con một chứng chỉ riêng |

- **Wildcard bắt buộc dùng DNS-01.** Đây là quy định của Let's Encrypt, HTTP-01 không cấp được chứng chỉ wildcard.
- HTTP-01 chỉ hợp để xin cho một vài tên cố định (ví dụ chỉ `deployra.<domain>` và `api.deployra.<domain>`). Nếu mỗi service xin một chứng chỉ riêng thì sẽ nhanh chạm giới hạn số chứng chỉ của Let's Encrypt, nên không dùng cho `{slug}.deployra.<domain>`.
- Cấu hình hiện tại dùng **DNS-01 qua Route53**. Nếu DNS ở nơi khác, đổi `ACME_DNS_PROVIDER` theo nhà cung cấp (xem danh sách provider trong tài liệu Traefik) và truyền token của họ bằng biến môi trường cho container `traefik`. Không ghi token vào repo.

### Thử bằng Let's Encrypt staging trước

Let's Encrypt có giới hạn số lần xin chứng chỉ thật. Lần đầu nên dùng môi trường thử: thêm tạm một dòng vào `command` của service `traefik` trong `docker-compose.stack.yml`:

```
      - --certificatesresolvers.le.acme.caserver=https://acme-staging-v02.api.letsencrypt.org/directory
```

Chứng chỉ staging trình duyệt sẽ báo "không tin cậy", nhưng chứng minh luồng DNS-01 chạy đúng. Chạy ổn thì **xoá dòng đó**,
xoá file chứng chỉ cũ rồi chạy lại để xin chứng chỉ thật:

```bash
docker compose -f docker-compose.stack.yml down
docker volume rm deployra-stack_letsencrypt
docker compose -f docker-compose.stack.yml up -d
```

## Bước 4. Kiểm tra

```bash
docker compose -f docker-compose.stack.yml logs traefik | grep -i -E "acme|certificate|error"
curl -I https://api.deployra.<domain>/actuator/health
```

Đúng khi lệnh `curl` trả `HTTP/2 200` và không báo lỗi chứng chỉ.

## Liên quan tới frontend

Trang service hiển thị URL dạng `https://{slug}.<BASE_DOMAIN>`. Khi build frontend cho máy thật, đặt:

```
VITE_BASE_DOMAIN=deployra.<domain>
```

## Phạm vi của task này

Task này chỉ chuẩn bị DNS và chứng chỉ. Việc tạo route Traefik riêng cho từng service
(`Host({slug}.deployra.<domain>)`) do **deploy engine** làm khi chạy container của người dùng, chưa nằm ở đây.
