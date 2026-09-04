package cn.aigc.dit.common.exception;

/**
 * 与 {@code CommonResult.code} 对齐的业务错误码。
 */
public final class ErrorCode {
    public static final int OK = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int CONFLICT = 409;
    public static final int INTERNAL = 500;

    private ErrorCode() {
    }
}
