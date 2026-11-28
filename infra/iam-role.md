# IAM Role cho EC2 (Task 3.2)

Máy EC2 lấy quyền AWS qua **instance profile** (IAM Role gắn vào instance).
**Không** tạo access key, không đưa key vào database, `.env` hay bất cứ file nào trong repo. Nếu thấy
`AWS_ACCESS_KEY_ID` trong file cấu hình thì đó là sai.

Thay các giá trị mẫu trước khi dùng: `ACCOUNT_ID`, `REGION` (ví dụ `ap-southeast-1`), `BUCKET`, `HOSTED_ZONE_ID`.

## Quyền cần (tối thiểu)

| Nhóm | Dùng cho | Gắn ngay? |
|---|---|---|
| CloudWatch Logs | Đẩy log của app lên CloudWatch | Có |
| Route53 | Traefik xin chứng chỉ wildcard bằng DNS-01 (xem `dns-wildcard.md`) | Có, nếu DNS nằm ở Route53 |
| S3 | Lưu và đọc file zip mã nguồn do người dùng upload | Chỉ khi có `S3SourceStorage` |
| ECR | Kéo / đẩy image riêng | Chưa, để sau |

> **Ghi chú:** `S3SourceStorage` là **file Java mới, chưa được viết** ở task này. Hiện trong code mới có interface `storage/SourceStorage.java`. Nhóm S3 bên dưới chỉ là chuẩn bị sẵn, chưa cần gắn cho tới khi lớp đó có.

## 1. Trust policy (cho phép EC2 dùng role)

`trust-policy.json`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Service": "ec2.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

## 2. Các policy quyền

### CloudWatch Logs: `deployra-cloudwatch-logs`

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams"
      ],
      "Resource": "arn:aws:logs:REGION:ACCOUNT_ID:log-group:/deployra/*:*"
    }
  ]
}
```

### Route53 cho Traefik DNS-01: `deployra-route53-acme`

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "route53:GetChange",
      "Resource": "arn:aws:route53:::change/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "route53:ChangeResourceRecordSets",
        "route53:ListResourceRecordSets"
      ],
      "Resource": "arn:aws:route53:::hostedzone/HOSTED_ZONE_ID"
    },
    {
      "Effect": "Allow",
      "Action": "route53:ListHostedZonesByName",
      "Resource": "*"
    }
  ]
}
```

### S3 cho file zip mã nguồn: `deployra-s3-source` (chỉ khi có `S3SourceStorage`)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::BUCKET"
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::BUCKET/*"
    }
  ]
}
```

### ECR (để sau, chưa gắn)

Khi nào dùng ECR mới thêm: `ecr:GetAuthorizationToken` (Resource `*`) và `ecr:BatchGetImage`,
`ecr:GetDownloadUrlForLayer`, `ecr:BatchCheckLayerAvailability`, `ecr:PutImage`,
`ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload` trên repository cụ thể.

## 3. Tạo role và instance profile bằng AWS CLI

```bash
aws iam create-role \
  --role-name deployra-ec2-role \
  --assume-role-policy-document file://trust-policy.json

# gắn từng policy ở mục 2 (lưu mỗi policy thành file .json cùng tên)
aws iam put-role-policy --role-name deployra-ec2-role \
  --policy-name deployra-cloudwatch-logs --policy-document file://deployra-cloudwatch-logs.json
aws iam put-role-policy --role-name deployra-ec2-role \
  --policy-name deployra-route53-acme --policy-document file://deployra-route53-acme.json

# instance profile + gắn role vào
aws iam create-instance-profile --instance-profile-name deployra-ec2-profile
aws iam add-role-to-instance-profile \
  --instance-profile-name deployra-ec2-profile --role-name deployra-ec2-role

# gắn instance profile vào máy EC2
aws ec2 associate-iam-instance-profile \
  --instance-id i-xxxx --iam-instance-profile Name=deployra-ec2-profile
```

Tạo bằng Console: IAM → Roles → Create role → chọn **AWS service / EC2** → thêm policy → đặt tên →
rồi EC2 → Actions → Security → **Modify IAM role**.

## 4. Cho container đọc được quyền của role (IMDSv2 hop limit)

Traefik chạy trong container nên cần lấy credentials từ metadata service của EC2. Với IMDSv2
mặc định (hop limit = 1) container không lấy được. Đặt hop limit = 2 và vẫn bắt buộc IMDSv2:

```bash
aws ec2 modify-instance-metadata-options \
  --instance-id i-xxxx \
  --http-tokens required \
  --http-put-response-hop-limit 2
```

## 5. Kiểm tra

Trên EC2 (SSH vào):

```bash
aws sts get-caller-identity          # phải hiện ARN dạng .../deployra-ec2-role/...
```

Nếu chưa cài AWS CLI trên máy, bỏ qua bước này và xem log Traefik khi xin chứng chỉ:
`docker compose -f docker-compose.stack.yml logs traefik`. Không được có lỗi `NoCredentialProviders`
hay `AccessDenied`.

## Lưu ý

- Quyền chỉ cấp đúng tài nguyên cần (log group `/deployra/*`, một hosted zone, một bucket), không dùng `*` rộng.
- Đổi `ACCOUNT_ID`, `REGION`, `BUCKET`, `HOSTED_ZONE_ID` rồi mới chạy lệnh. Không commit ID tài khoản thật nếu repo public.
