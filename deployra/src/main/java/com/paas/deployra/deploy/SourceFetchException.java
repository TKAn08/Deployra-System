package com.paas.deployra.deploy;

public class SourceFetchException extends RuntimeException {

    public SourceFetchException(String message) {
        super(message);
    }

    public SourceFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}