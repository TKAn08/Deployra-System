package com.paas.deployra.admin;

public class AdminForbiddenException extends RuntimeException {

    public AdminForbiddenException() {
        super("Cần quyền quản trị");
    }
}
