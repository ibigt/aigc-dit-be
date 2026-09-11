package cn.aigc.dit.server.exception;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.model.resp.CommonResult;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<CommonResult<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(httpStatus(e.getCode()))
                .body(CommonResult.failed(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResult<Void>> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatField)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(CommonResult.failed(ErrorCode.BAD_REQUEST, message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CommonResult<Void>> handleConstraint(ConstraintViolationException e) {
        return ResponseEntity.badRequest().body(CommonResult.failed(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<CommonResult<Void>> handleAuth(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResult.failed(ErrorCode.UNAUTHORIZED, "未登录"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<CommonResult<Void>> handleDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(CommonResult.failed(ErrorCode.FORBIDDEN, "无权限"));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientGone(AsyncRequestNotUsableException e) {
        log.warn("Client aborted before response was written: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResult<Void>> handle(Exception e) {
        if (isBrokenPipe(e)) {
            log.warn("Client aborted before response was written: {}", e.getMessage());
            return null;
        }
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(CommonResult.failed(ErrorCode.INTERNAL, "服务器内部错误"));
    }

    private static boolean isBrokenPipe(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (current instanceof java.io.IOException && message != null
                    && (message.contains("Broken pipe") || message.contains("Connection reset"))) {
                return true;
            }
        }
        return false;
    }

    private String formatField(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private static HttpStatus httpStatus(int code) {
        HttpStatus status = HttpStatus.resolve(code);
        return status != null ? status : HttpStatus.BAD_REQUEST;
    }
}
