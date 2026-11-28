#!/bin/bash
# =====================================================================
# infra/user-data.sh: dựng máy EC2 (Ubuntu 22.04 / 24.04) chạy stack Deployra.
#
# Dán nội dung file này vào ô "User data" khi tạo instance (Advanced details),
# hoặc chạy tay trên máy bằng: sudo bash user-data.sh
#
# Làm gì: cài Docker + compose plugin -> clone repo -> tạo deployra/.env
#         (mật khẩu DB và khoá mã hoá sinh ngẫu nhiên, không nằm trong repo)
#         -> docker compose -f docker-compose.stack.yml up -d --build
#
# Không sửa docker-compose.yml cũ (chỉ Postgres dùng cho dev local).
# Log chạy script: /var/log/deployra-bootstrap.log
# =====================================================================
set -euo pipefail
exec > >(tee -a /var/log/deployra-bootstrap.log) 2>&1
export DEBIAN_FRONTEND=noninteractive

# ---------- SỬA CÁC GIÁ TRỊ NÀY TRƯỚC KHI DÙNG ----------
REPO_URL="https://github.com/<org>/Deployra-System.git"
REPO_BRANCH="main"
BASE_DOMAIN="deployra.example.com"          # service có URL https://{slug}.BASE_DOMAIN
APP_DOMAIN="api.deployra.example.com"       # địa chỉ API của hệ thống
ACME_EMAIL="admin@example.com"              # email nhận thông báo của Let's Encrypt
AWS_REGION="ap-southeast-1"
ADMIN_EMAILS="demo@deployra.local"          # email admin, phân tách bằng dấu phẩy
APP_DIR="/opt/deployra-system"
# Repo private: dùng deploy key hoặc token đọc từ SSM Parameter Store,
# KHÔNG ghi token thẳng vào file này hay vào repo.
# ---------------------------------------------------------

echo "==> [1/5] Cập nhật hệ thống và cài gói cơ bản"
apt-get update -y
apt-get install -y ca-certificates curl gnupg git openssl

echo "==> [2/5] Cài Docker Engine + compose plugin (kho chính thức của Docker)"
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list

# Xoay vòng log của container để không đầy ổ đĩa (ghi trước khi Docker khởi động)
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
JSON

apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
usermod -aG docker ubuntu || true     # cho phép user ubuntu chạy docker không cần sudo

echo "==> [3/5] Lấy mã nguồn về ${APP_DIR}"
if [ -d "${APP_DIR}/.git" ]; then
  git -C "${APP_DIR}" pull --ff-only
else
  git clone --branch "${REPO_BRANCH}" "${REPO_URL}" "${APP_DIR}"
fi
cd "${APP_DIR}/deployra"

echo "==> [4/5] Tạo deployra/.env (chỉ tạo nếu chưa có)"
# Không ghi đè .env đã có: đổi DB_PASSWORD hay DEPLOYRA_CRYPTO_KEY sau khi chạy lần đầu
# sẽ làm app mất kết nối DB và không giải mã được biến môi trường đã lưu.
if [ ! -f .env ]; then
  umask 077
  DB_PASSWORD="$(openssl rand -hex 24)"
  CRYPTO_KEY="$(openssl rand -base64 32)"
  cat > .env <<ENVFILE
DB_USER=deployra
DB_PASSWORD=${DB_PASSWORD}
DEPLOYRA_CRYPTO_KEY=${CRYPTO_KEY}
BASE_DOMAIN=${BASE_DOMAIN}
APP_DOMAIN=${APP_DOMAIN}
ACME_EMAIL=${ACME_EMAIL}
AWS_REGION=${AWS_REGION}
DEPLOYRA_ADMIN_EMAILS=${ADMIN_EMAILS}
# Khi pom.xml đã có Flyway (xem infra/flyway-checklist.md) thì đổi thành:
#   DDL_AUTO=validate  và  SPRING_PROFILES_ACTIVE=prod
DDL_AUTO=update
SPRING_PROFILES_ACTIVE=
ENVFILE
  chmod 600 .env
  echo ".env đã được tạo (mật khẩu và khoá sinh ngẫu nhiên, chỉ nằm trên máy này)."
else
  echo ".env đã tồn tại, giữ nguyên."
fi

echo "==> [5/5] Khởi động stack"
docker compose -f docker-compose.stack.yml up -d --build
docker compose -f docker-compose.stack.yml ps

echo "Xong. Kiểm tra: curl -I https://${APP_DOMAIN}/actuator/health"
