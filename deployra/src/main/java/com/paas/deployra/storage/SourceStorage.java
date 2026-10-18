package com.paas.deployra.storage;

import com.paas.deployra.paas.SourceUpload;

import java.io.InputStream;

/**
 * Nơi lưu file zip mã nguồn. Bản đầu dùng ổ đĩa (LOCAL), sau đó thêm bản S3/R2 mà không phải sửa DB:
 * {@code source_uploads.storage_backend} cho biết dùng bản nào, {@code object_key} là đường dẫn trong kho.
 * Thông tin truy cập (IAM Role, access key) đặt trong cấu hình, không lưu DB.
 */
public interface SourceStorage {

    SourceUpload.StorageBackend backend();

    void put(String objectKey, InputStream data, long sizeBytes);

    InputStream open(String objectKey);

    void delete(String objectKey);
}
