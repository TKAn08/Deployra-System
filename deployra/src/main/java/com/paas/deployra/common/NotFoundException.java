package com.paas.deployra.common;

/**
 * Không tìm thấy tài nguyên, hoặc tài nguyên không thuộc về người dùng. Dùng
 * chung một lỗi cho cả hai
 * trường hợp để không lộ việc "có tồn tại nhưng của người khác". Tầng API đổi
 * thành HTTP 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}