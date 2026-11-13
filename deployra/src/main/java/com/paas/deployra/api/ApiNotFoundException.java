package com.paas.deployra.api;

/** Tài nguyên không tồn tại hoặc không thuộc user đang đăng nhập. */
public class ApiNotFoundException extends RuntimeException {

    public ApiNotFoundException(String message) {
        super(message);
    }
}
