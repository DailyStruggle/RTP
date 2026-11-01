package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

/**
 * Exception thrown when Redis replies with an error (prefixed with '-').
 *
 * @governedBy ADR-113 (Java 17 Bytecode Baseline)
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 */
public class RespException extends RuntimeException {
    private static final long serialVersionUID = 1L;

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
