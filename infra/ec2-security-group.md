# Security group cho EC2 t3.medium (Task 3.1)

Một security group duy nhất cho máy chạy Deployra (Traefik + app + Postgres trong Docker).
Nguyên tắc: **chỉ mở cổng mà người ngoài thật sự cần**. Mọi thứ còn lại đóng.

## Inbound (chiều vào)

| Cổng | Giao thức | Nguồn | Để làm gì |
|---|---|---|---|
| 22 | TCP | **IP của admin** (`x.x.x.x/32`), không mở `0.0.0.0/0` | SSH quản trị máy |
| 80 | TCP | `0.0.0.0/0`, `::/0` | Traefik nhận HTTP, chuyển hướng sang HTTPS, và để Let's Encrypt kiểm tra HTTP-01 |
| 443 | TCP | `0.0.0.0/0`, `::/0` | Traefik nhận HTTPS cho API và các service của người dùng |

**Không mở** ra internet:

- **8080**: cổng của app Spring Boot. App chỉ nhận request qua Traefik trong mạng Docker (`deployra-net`).
- **5432**: Postgres. Chỉ app trong mạng Docker kết nối được. `docker-compose.stack.yml` cũng không publish cổng này ra máy chủ.

## Outbound (chiều ra)

Để mặc định **tất cả** (`All traffic`, `0.0.0.0/0`). Máy cần ra ngoài để:

- kéo image Docker (Docker Hub), cài gói `apt`
- `git clone` / `git pull` từ GitHub
- Let's Encrypt cấp chứng chỉ, gọi API Route53 (nếu dùng DNS-01)
- ghi log lên CloudWatch, đọc/ghi S3

## Tạo bằng AWS CLI

Thay `vpc-xxxx` bằng VPC của bạn, và sửa `ADMIN_IP` trong `sg.json` bằng IP thật.
Lấy IP hiện tại của bạn:

```bash
curl https://checkip.amazonaws.com
```

Trong `infra/sg.json`, đổi `ADMIN_IP/32` thành, ví dụ, `203.0.113.10/32`. Rồi:

```bash
aws ec2 create-security-group \
  --group-name deployra-sg \
  --description "Deployra EC2: SSH admin, HTTP, HTTPS" \
  --vpc-id vpc-xxxx

aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxx \
  --ip-permissions file://infra/sg.json
```

`sg-xxxx` là `GroupId` mà lệnh đầu in ra. Outbound mặc định đã là cho phép tất cả nên không cần lệnh thêm.

### Hoặc tạo trên AWS Console

EC2 → Security Groups → **Create security group** → thêm 3 inbound rule theo bảng trên → để nguyên outbound mặc định → gắn security group vào instance (Actions → Security → Change security groups).

## Kiểm tra sau khi gắn

Chạy từ máy khác (không phải trong EC2):

```bash
curl -m 5 http://<ELASTIC_IP>        # có phản hồi từ Traefik (404 hoặc chuyển hướng là bình thường)
curl -m 5 http://<ELASTIC_IP>:8080   # phải hết thời gian chờ (timeout)
```

Trên Windows PowerShell:

```powershell
Test-NetConnection <ELASTIC_IP> -Port 5432   # TcpTestSucceeded phải là False
Test-NetConnection <ELASTIC_IP> -Port 8080   # TcpTestSucceeded phải là False
```

## Lưu ý

- IP admin thay đổi (nhà mạng cấp IP động) thì SSH bị chặn. Cập nhật lại rule cổng 22, hoặc dùng AWS Systems Manager Session Manager để không cần mở cổng 22.
- Không dùng `0.0.0.0/0` cho cổng 22, kể cả khi chạy thử.
