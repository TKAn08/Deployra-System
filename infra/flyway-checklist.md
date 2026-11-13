# Flyway + validate: checklist trước khi lên máy thật (Task 4.3)

Mục tiêu: lên EC2 thì bảng do Flyway tạo (`V1__baseline.sql`), Hibernate chỉ kiểm tra (`ddl-auto=validate`).
Máy dev vẫn dùng `ddl-auto=update` như cũ. **Không sửa `pom.xml` / `application.properties` trong PR này.**

Các file liên quan:

| File | Vai trò |
|---|---|
| `deployra/src/main/resources/db/migration/V1__baseline.sql` | Tạo 15 bảng, khớp entity |
| `deployra/src/main/resources/application-prod.properties` | `validate` + bật Flyway |

> Bảng người dùng tên **`users`** (entity `User` đã đổi từ `user` vì trùng từ khoá PostgreSQL). V1 dùng đúng tên này, nên PR đổi tên `User.java` phải được gộp trước hoặc cùng lúc.

---

## Bước 1. Chạy thử V1 trên DB trống

Dùng container Postgres local đang chạy (`deployra-postgres-1`). Chạy trong `D:\Deployra-System\deployra`:

```powershell
docker exec deployra-postgres-1 psql -U deployra -d postgres -c "CREATE DATABASE deployra_check;"
docker cp src\main\resources\db\migration\V1__baseline.sql deployra-postgres-1:/tmp/V1.sql
docker exec deployra-postgres-1 psql -U deployra -d deployra_check -v ON_ERROR_STOP=1 -f /tmp/V1.sql
```

Đúng khi không có dòng `ERROR`. Đếm bảng, phải ra **15**:

```powershell
docker exec deployra-postgres-1 psql -U deployra -d deployra_check -Atc "select count(*) from information_schema.tables where table_schema='public'"
```

## Bước 2. Chạy app với `DDL_AUTO=validate` trên DB đã có bảng

Dev vẫn để `update`, chỉ lần kiểm tra này mới đặt `validate`:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/deployra_check"
$env:DDL_AUTO = "validate"
.\mvnw.cmd spring-boot:run
```

- Đúng khi log có `Started DeployraApplication`.
- Sai khi log có `Schema-validation: missing table/column` hoặc `wrong column type`: V1 lệch entity ở đúng chỗ log nêu, sửa `V1__baseline.sql` (chưa ai áp dụng nên sửa trực tiếp được).

Xong thì trả biến về như cũ: `Remove-Item Env:DB_URL, Env:DDL_AUTO`.

## Bước 3. Đối chiếu với schema Hibernate đang sinh ra

Dump schema local (DB `deployra` đã chạy qua seeder) và schema của `deployra_check`, rồi so:

```powershell
docker exec deployra-postgres-1 pg_dump -U deployra -d deployra --schema-only --no-owner > schema_local.sql
docker exec deployra-postgres-1 pg_dump -U deployra -d deployra_check --schema-only --no-owner > schema_v1.sql
Compare-Object (Get-Content schema_local.sql) (Get-Content schema_v1.sql)
```

Khác biệt chấp nhận được: tên ràng buộc (Hibernate đặt tên kiểu `FK...`, V1 đặt tên dễ đọc), thứ tự khai báo.
Khác biệt cần xem lại: kiểu cột, `NOT NULL`, độ dài `varchar`, cột thiếu hoặc thừa.

Dọn dẹp: `docker exec deployra-postgres-1 psql -U deployra -d postgres -c "DROP DATABASE deployra_check;"`

## Bước 4. Mở PR riêng

PR chỉ gồm SQL và file properties, không đụng `pom.xml`:

```powershell
git checkout -b feat/flyway-baseline
git add deployra/src/main/resources/db/migration/V1__baseline.sql deployra/src/main/resources/application-prod.properties infra/
git commit -m "Add Flyway baseline V1 and prod profile"
git push -u origin feat/flyway-baseline
```

## Bước 5. Ticket cho nhóm: thêm Flyway vào `pom.xml`

> **Tiêu đề:** Thêm Flyway vào `deployra/pom.xml` (người khác hoặc buổi chung sửa pom)
>
> Spring Boot 4 tách Flyway thành starter riêng, và PostgreSQL cần thêm module database riêng. Thêm 2 dependency (phiên bản do Spring Boot quản lý, không ghi `<version>`):
>
> ```xml
> <dependency>
>     <groupId>org.springframework.boot</groupId>
>     <artifactId>spring-boot-starter-flyway</artifactId>
> </dependency>
> <dependency>
>     <groupId>org.flywaydb</groupId>
>     <artifactId>flyway-database-postgresql</artifactId>
> </dependency>
> ```
>
> Sau khi thêm:
> 1. Trong `src/test/resources/application-test.properties` thêm `spring.flyway.enabled=false`, vì test dùng H2 với `create-drop`, không chạy migration.
> 2. Chạy `./mvnw verify`, phải xanh.
> 3. Chạy lại Bước 1 và 2 ở trên với app thật.
>
> Chưa gộp ticket này thì **không** đặt `SPRING_PROFILES_ACTIVE=prod` trên EC2 (DB trống sẽ không có bảng nào).

## Thứ tự triển khai lần đầu trên EC2

1. Ticket pom đã gộp, CI xanh.
2. Đặt `SPRING_PROFILES_ACTIVE=prod` trong `.env` của stack (và bỏ `DDL_AUTO` hoặc để `validate`).
3. Khởi động stack với DB trống: Flyway chạy `V1`, Hibernate `validate`, rồi seeder nạp dữ liệu gói và demo.

Trước khi có Flyway, stack vẫn chạy được với `DDL_AUTO=update` (giá trị mặc định trong `docker-compose.stack.yml`).

## Quy tắc về sau

- Đã áp dụng `V1` lên DB nào thì **không sửa file V1 nữa**. Mọi thay đổi schema thêm file mới: `V2__ten_thay_doi.sql`, `V3__...`.
- Nếu DB thật đã được Hibernate tạo bảng sẵn, bật `spring.flyway.baseline-on-migrate=true` (dòng đang comment trong `application-prod.properties`).
