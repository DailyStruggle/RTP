package io.github.dailystruggle.database.resp;

import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespConnection;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespEndpoint;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Pure-Java RESP2 TCP/TLS Redis connection ({@code :database-api}).
 *
 * @governedBy ADR-113, ADR-115
 * @trace S-004, S-005
 */
public class RedisRespConnection implements Closeable {

    private final RespConnection delegate;

    public RedisRespConnection(RespConnection delegate) {
        this.delegate = delegate;
    }

    public RedisRespConnection(String host, int port, int timeoutMs, String password) throws IOException {
        this.delegate = new RespConnection(host, port, timeoutMs, password);
    }

    public RedisRespConnection(RespEndpoint endpoint, int timeoutMs, String password) throws IOException {
        this.delegate = new RespConnection(endpoint, timeoutMs, password);
    }

    public Object executeCommand(String... args) throws IOException {
        return delegate.executeCommand(args);
    }

    public Object executeCommandBytes(List<byte[]> args) throws IOException {
        return delegate.executeCommandBytes(args);
    }

    public String get(String key) throws IOException {
        return delegate.get(key);
    }

    public void set(String key, String value) throws IOException {
        delegate.set(key, value);
    }

    public void setex(String key, int seconds, String value) throws IOException {
        delegate.setex(key, seconds, value);
    }

    public String ping() throws IOException {
        return delegate.ping();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
