package com.kairon.saros.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：渲染为 {"detail": "..."}（与阶段二 FastAPI HTTPException 响应形状一致）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
