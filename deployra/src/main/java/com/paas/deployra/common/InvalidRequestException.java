package com.paas.deployra.common;

/**
 * Yêu cầu sai do người dùng (file không phải zip, service không nhận mã
 * nguồn...). Tầng API đổi thành HTTP 400.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}