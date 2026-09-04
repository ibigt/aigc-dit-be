package cn.aigc.dit.common.model.resp;

import java.io.Serializable;

/**
 * 统一响应：{@code code / message / data}，对齐 model-service CommonResult。
 */
public class CommonResult<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final int OK = 200;
    private static final int INTERNAL_SERVER_ERROR = 500;
    private static final String OK_REASON_PHRASE = "OK";
    private static final String INTERNAL_SERVER_ERROR_PHRASE = "Internal Server Error";

    private int code;
    private String message;
    private T data;
    private String traceId;

    private CommonResult() {
    }

    private CommonResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> CommonResult<T> success(T data) {
        return new CommonResult<>(OK, OK_REASON_PHRASE, data);
    }

    public static <T> CommonResult<T> success() {
        return success(null);
    }

    public static <T> CommonResult<T> success(T data, String message) {
        return new CommonResult<>(OK, message, data);
    }

    public static <T> CommonResult<T> failed(int code, String message) {
        return new CommonResult<>(code, message, null);
    }

    public static <T> CommonResult<T> failed() {
        return failed(INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR_PHRASE);
    }

    public static <T> CommonResult<T> failed(String message) {
        return failed(INTERNAL_SERVER_ERROR, message);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public boolean isSuccess() {
        return code == OK;
    }
}
