package com.kairon.saros.common;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * 统一异常渲染：业务异常 → {"detail": 字符串}；参数校验失败 → 422
 * {"detail": [{loc, msg, type}]}（对齐 FastAPI HTTPException / ValidationError 形状）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ApiErrorBody(String detail) {
    }

    public record ValidationItem(List<String> loc, String msg, String type) {
    }

    public record ValidationBody(List<ValidationItem> detail) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorBody> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiErrorBody(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationBody> handleValidation(MethodArgumentNotValidException e) {
        List<ValidationItem> items = e.getBindingResult().getFieldErrors().stream()
                .map(this::toItem)
                .toList();
        return ResponseEntity.status(HttpStatusCode.valueOf(422)).body(new ValidationBody(items));
    }

    private ValidationItem toItem(FieldError err) {
        String type = err.getCode() == null ? "value_error" : err.getCode();
        return new ValidationItem(List.of("body", err.getField()), err.getDefaultMessage(), type);
    }

    /** 参数非法时手动抛出的 422（如 mastery 超出 0-5、top_k 超出 1-50）。 */
    public static class ValidationException extends RuntimeException {
        private final String field;

        public ValidationException(String field, String msg) {
            super(msg);
            this.field = field;
        }

        public String getField() {
            return field;
        }
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ValidationBody> handleManualValidation(ValidationException e) {
        return ResponseEntity.status(HttpStatusCode.valueOf(422))
                .body(new ValidationBody(List.of(
                        new ValidationItem(List.of("body", e.getField()), e.getMessage(), "value_error"))));
    }
}
