package com.paas.deployra.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Đổi AdminForbiddenException thành HTTP 403. */
@RestControllerAdvice
public class AdminExceptionAdvice {

    @ExceptionHandler(AdminForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(AdminForbiddenException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "forbidden");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}