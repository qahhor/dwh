package com.greenwhite.dwh.cp.error;

import org.springframework.http.HttpStatus;

public class CpApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public CpApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }
}
