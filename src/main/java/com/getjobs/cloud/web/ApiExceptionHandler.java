package com.getjobs.cloud.web;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(basePackages = "com.getjobs.cloud")
@Profile("api")
@Slf4j
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        if (exception.retryAfterSeconds() > 0) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
        }
        return new ResponseEntity<>(
                ApiResponse.failure(new ApiError(
                        exception.code(), exception.getMessage(), exception.fieldErrors(), exception.retryable()
                )),
                headers,
                exception.status()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        List<ApiError.FieldViolation> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldViolation)
                .toList();
        return ResponseEntity.badRequest().body(ApiResponse.failure(new ApiError(
                "VALIDATION_ERROR", "请求参数不正确", fields, false
        )));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        List<ApiError.FieldViolation> fields = exception.getConstraintViolations().stream()
                .map(violation -> new ApiError.FieldViolation(
                        violation.getPropertyPath().toString(), violation.getMessage()
                ))
                .toList();
        return ResponseEntity.badRequest().body(ApiResponse.failure(new ApiError(
                "VALIDATION_ERROR", "请求参数不正确", fields, false
        )));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedJson() {
        return ResponseEntity.badRequest().body(ApiResponse.failure(new ApiError(
                "MALFORMED_JSON", "请求参数格式不正确", List.of(), false
        )));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.failure(new ApiError(
                "PAYLOAD_TOO_LARGE", "简历文件不能超过 10 MiB", List.of(), false
        )));
    }

    @ExceptionHandler({
            MissingRequestHeaderException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleMissingOrInvalidRequestValue(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(new ApiError(
                "VALIDATION_ERROR", "请求参数不正确", List.of(), false
        )));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        // Only the stable exception types plus the MDC request id are logged:
        // database or driver messages may carry bound values, token hashes or
        // user input and must never reach logs. The root-cause TYPE (never the
        // message) is logged so SQL grammar regressions stay diagnosable.
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        log.error("Cloud API 未处理异常，类型={}，根因类型={}",
                exception.getClass().getSimpleName(), root.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(new ApiError(
                "INTERNAL_ERROR", "服务暂时无法处理请求", List.of(), false
        )));
    }

    private ApiError.FieldViolation fieldViolation(FieldError error) {
        return new ApiError.FieldViolation(error.getField(), error.getDefaultMessage());
    }
}
