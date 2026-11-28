package com.paas.deployra.api;

/** Chưa đăng nhập hoặc sai email / mật khẩu. RestExceptionAdvice đổi thành HTTP 401. */
public class ApiUnauthorizedException extends RuntimeException {

    public ApiUnauthorizedException(String message) {
        super(message);
    }
}
