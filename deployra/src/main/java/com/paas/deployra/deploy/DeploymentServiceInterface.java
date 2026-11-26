package com.paas.deployra.deploy;

import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.Deployment;
import com.paas.deployra.paas.SourceUpload;

/**
 * Hợp đồng giữa API (Người 3), webhook, rollback và deploy engine (Người 1).
 * Bên gọi chỉ cần biết hai hàm này; hàng đợi, máy trạng thái và worker là việc
 * bên trong.
 *
 * <p>
 * Cả hai hàm tạo một {@link Deployment} ở trạng thái {@code QUEUED}, lưu xuống
 * DB (commit xong)
 * rồi mới trả về, để worker đọc được ngay.
 */
public interface DeploymentServiceInterface {

    /**
     * Deploy từ GitHub: lấy {@code repoUrl} và {@code branch} đang lưu trong
     * service.
     */
    Deployment enqueue(AppService service, Deployment.Trigger trigger);

    /**
     * Deploy từ file zip đã upload (đã nằm trong kho và có dòng
     * {@code source_uploads}).
     */
    Deployment enqueue(AppService service, Deployment.Trigger trigger, SourceUpload upload);
}