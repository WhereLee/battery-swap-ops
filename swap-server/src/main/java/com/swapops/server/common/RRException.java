package com.swapops.server.common;

/**
 * 业务异常（HTTP 400 语义，code=1 或缺省）。
 */
public class RRException extends RuntimeException {

    private final int code;

    public RRException(String message) {
        this(1, message);
    }

    public RRException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
