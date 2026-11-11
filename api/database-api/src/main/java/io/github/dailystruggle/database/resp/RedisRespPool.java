package io.github.dailystruggle.database.resp;

import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespEndpoint;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespPool;
import java.io.Closeable;

/**
 * Thread-safe connection pool for {@link RedisRespConnection} ({@code :database-api}).
 *
 * @governedBy ADR-113, ADR-115
 * @trace S-004, S-005
 */
public class RedisRespPool implements Closeable {

    private final RespPool delegate;

    public RedisRespPool(String host, int port, int timeoutMs, String password, int maxTotal) {
        this.delegate = new RespPool(host, port, timeoutMs, password, maxTotal);
    }

    public RedisRespPool(RespEndpoint endpoint, int timeoutMs, String password, int maxTotal) {
        this.delegate = new RespPool(endpoint, timeoutMs, password, maxTotal);
    }

    public RedisRespPool(String host, int port, String password) {
        this.delegate = new RespPool(host, port, password);
    }

    public RedisRespConnection getResource() {
        return new RedisRespConnection(delegate.getResource());
    }

    @Override
    public void close() {
        delegate.close();
    }
}
