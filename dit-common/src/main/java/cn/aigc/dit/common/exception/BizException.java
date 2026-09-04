package cn.aigc.dit.common.exception;

/**
 * 业务异常。Controller 层由全局处理器转成 {@code CommonResult}。
 */
public class BizException extends RuntimeException {
    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(ErrorCode.BAD_REQUEST, message);
    }

    public int getCode() {
        return code;
    }
}
