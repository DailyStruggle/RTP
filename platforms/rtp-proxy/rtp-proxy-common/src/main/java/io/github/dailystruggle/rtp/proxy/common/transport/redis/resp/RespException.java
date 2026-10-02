package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

/**
 * Exception thrown when Redis replies with an error (prefixed with '-').
 */
public class RespException extends RuntimeException {

    public RespException(String message) {
        super(message);
    }

    public RespException(String message, Throwable cause) {
        super(message, cause);
    }

    public boolean isNoScript() {
        String msg = getMessage();
        return msg != null && msg.toUpperCase().startsWith("NOSCRIPT");
    }
}
