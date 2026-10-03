package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe connection pool for {@link RespConnection}.
 * Replaces JedisPool and Apache Commons Pool2 with a simple bounded ArrayBlockingQueue.
 */
public class RespPool implements Closeable {

    private final String host;
    private final int port;
    private final int timeoutMs;
    private final String password;
    private final int maxTotal;

    private final BlockingQueue<RespConnection> pool;
    private final AtomicInteger createdCount = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RespPool(String host, int port, int timeoutMs, String password, int maxTotal) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 2000;
        this.password = (password != null && !password.isEmpty()) ? password : null;
        this.maxTotal = maxTotal > 0 ? maxTotal : 16;
        this.pool = new ArrayBlockingQueue<>(this.maxTotal);
    }

    public RespPool(String host, int port, String password) {
        this(host, port, 2000, password, 16);
    }

    /**
     * Borrows a connection from the pool, or creates a new one if below maxTotal.
     */
    public RespConnection getResource() {
        if (closed.get()) {
            throw new IllegalStateException("RespPool is closed");
        }
        RespConnection conn = pool.poll();
        if (conn != null) {
            if (!conn.isBroken()) {
                return new PooledConnection(conn);
            } else {
                conn.close();
                createdCount.decrementAndGet();
            }
        }

        // Try creating a new connection if under limit
        while (true) {
            int current = createdCount.get();
            if (current < maxTotal) {
                if (createdCount.compareAndSet(current, current + 1)) {
                    try {
                        RespConnection newConn = new RespConnection(host, port, timeoutMs, password);
                        return new PooledConnection(newConn);
                    } catch (IOException e) {
                        createdCount.decrementAndGet();
                        throw new RuntimeException("Could not create Redis connection to " + host + ":" + port, e);
                    }
                }
            } else {
                break;
            }
        }

        // Wait for an idle connection
        try {
            conn = pool.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (conn == null) {
                throw new RuntimeException("Timeout waiting for idle connection from RespPool");
            }
            if (conn.isBroken()) {
                conn.close();
                createdCount.decrementAndGet();
                return getResource();
            }
            return new PooledConnection(conn);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for connection", e);
        }
    }

    private void returnResource(RespConnection conn) {
        if (closed.get() || conn.isBroken()) {
            conn.close();
            createdCount.decrementAndGet();
            return;
        }
        if (!pool.offer(conn)) {
            conn.close();
            createdCount.decrementAndGet();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RespConnection conn;
        while ((conn = pool.poll()) != null) {
            conn.close();
            createdCount.decrementAndGet();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * AutoCloseable wrapper that returns the underlying connection to the pool upon close().
     */
    private class PooledConnection extends RespConnection {
        private final RespConnection delegate;
        private boolean returned = false;

        PooledConnection(RespConnection delegate) {
            super(host, port, timeoutMs, password, false);
            this.delegate = delegate;
        }

        @Override
        public synchronized Object executeCommand(String... args) throws IOException {
            return delegate.executeCommand(args);
        }

        @Override
        public synchronized String get(String key) throws IOException {
            return delegate.get(key);
        }

        @Override
        public synchronized void set(String key, String val) throws IOException {
            delegate.set(key, val);
        }

        @Override
        public synchronized void setex(String key, long seconds, String val) throws IOException {
            delegate.setex(key, seconds, val);
        }

        @Override
        public synchronized String set(String key, String val, String nxxx, String expx, long time) throws IOException {
            return delegate.set(key, val, nxxx, expx, time);
        }

        @Override
        public synchronized long del(String... keys) throws IOException {
            return delegate.del(keys);
        }

        @Override
        public synchronized long ttl(String key) throws IOException {
            return delegate.ttl(key);
        }

        @Override
        public synchronized long expire(String key, long seconds) throws IOException {
            return delegate.expire(key, seconds);
        }

        @Override
        public synchronized long publish(String channel, String msg) throws IOException {
            return delegate.publish(channel, msg);
        }

        @Override
        public synchronized String hget(String key, String field) throws IOException {
            return delegate.hget(key, field);
        }

        @Override
        public synchronized void hset(String key, String field, String val) throws IOException {
            delegate.hset(key, field, val);
        }

        @Override
        public synchronized void hset(String key, java.util.Map<String, String> hash) throws IOException {
            delegate.hset(key, hash);
        }

        @Override
        public synchronized java.util.Map<String, String> hgetAll(String key) throws IOException {
            return delegate.hgetAll(key);
        }

        @Override
        public synchronized String scriptLoad(String script) throws IOException {
            return delegate.scriptLoad(script);
        }

        @Override
        public synchronized Object eval(String script, java.util.List<String> keys, java.util.List<String> args) throws IOException {
            return delegate.eval(script, keys, args);
        }

        @Override
        public synchronized Object evalsha(String sha1, java.util.List<String> keys, java.util.List<String> args) throws IOException {
            return delegate.evalsha(sha1, keys, args);
        }

        @Override
        public synchronized ScanResult scan(String cursor, String pattern, int count) throws IOException {
            return delegate.scan(cursor, pattern, count);
        }

        @Override
        public synchronized long llen(String key) throws IOException {
            return delegate.llen(key);
        }

        @Override
        public synchronized String ping() throws IOException {
            return delegate.ping();
        }

        @Override
        public boolean isBroken() {
            return delegate.isBroken();
        }

        @Override
        public synchronized void close() {
            if (!returned) {
                returned = true;
                returnResource(delegate);
            }
        }
    }
}
