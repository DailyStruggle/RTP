package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

/**
 * Encapsulates a raw TCP connection to a Redis server speaking RESP2.
 */
public class RespConnection implements Closeable {

    private final String host;
    private final int port;
    private final int timeoutMs;
    private final String password;

    private Socket socket;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private boolean broken;

    public RespConnection(String host, int port, int timeoutMs, String password) throws IOException {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.password = password;
        connect();
    }

    RespConnection(String host, int port, int timeoutMs, String password, boolean connectNow) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.password = password;
        if (connectNow) {
            try {
                connect();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void connect() throws IOException {
        this.socket = new Socket();
        this.socket.setTcpNoDelay(true);
        this.socket.setKeepAlive(true);
        this.socket.setSoTimeout(timeoutMs);
        this.socket.connect(new InetSocketAddress(host, port), timeoutMs);
        this.in = new BufferedInputStream(socket.getInputStream(), 8192);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 8192);
        this.broken = false;

        if (password != null && !password.isEmpty()) {
            auth(password);
        }
    }

    public synchronized void auth(String pwd) throws IOException {
        executeCommand("AUTH", pwd);
    }

    public synchronized String ping() throws IOException {
        Object res = executeCommand("PING");
        return res instanceof String ? (String) res : RespProtocol.toUtf8((byte[]) res);
    }

    public synchronized Object executeCommand(String... args) throws IOException {
        if (broken || socket.isClosed()) {
            throw new IOException("Connection is closed or broken");
        }
        try {
            if (args.length > 0) {
                RespProtocol.writeCommand(out, args);
            }
            return RespProtocol.readReply(in);
        } catch (IOException e) {
            broken = true;
            throw e;
        } catch (RespException re) {
            // Redis error reply does NOT break the TCP connection
            throw re;
        }
    }

    public synchronized Object executeCommandBytes(List<byte[]> args) throws IOException {
        if (broken || socket.isClosed()) {
            throw new IOException("Connection is closed or broken");
        }
        try {
            RespProtocol.writeCommand(out, args);
            return RespProtocol.readReply(in);
        } catch (IOException e) {
            broken = true;
            throw e;
        }
    }

    public synchronized String get(String key) throws IOException {
        Object rep = executeCommand("GET", key);
        if (rep == null) return null;
        if (rep instanceof byte[] bytes) return RespProtocol.toUtf8(bytes);
        return rep.toString();
    }

    public synchronized void set(String key, String val) throws IOException {
        executeCommand("SET", key, val);
    }

    public synchronized void setex(String key, long seconds, String val) throws IOException {
        executeCommand("SETEX", key, String.valueOf(seconds), val);
    }

    /**
     * Executes SET with NX/XX and EX/PX.
     * e.g. set("key", "val", "NX", "PX", 5000)
     */
    public synchronized String set(String key, String val, String nxxx, String expx, long time) throws IOException {
        List<String> cmd = new ArrayList<>(6);
        cmd.add("SET");
        cmd.add(key);
        cmd.add(val);
        if (nxxx != null && !nxxx.isEmpty()) {
            cmd.add(nxxx);
        }
        if (expx != null && !expx.isEmpty()) {
            cmd.add(expx);
            cmd.add(String.valueOf(time));
        }
        Object res = executeCommand(cmd.toArray(new String[0]));
        if (res == null) return null;
        if (res instanceof String s) return s;
        return RespProtocol.toUtf8((byte[]) res);
    }

    public synchronized long del(String... keys) throws IOException {
        String[] cmd = new String[keys.length + 1];
        cmd[0] = "DEL";
        System.arraycopy(keys, 0, cmd, 1, keys.length);
        Object rep = executeCommand(cmd);
        return rep instanceof Long l ? l : 0L;
    }

    public synchronized long ttl(String key) throws IOException {
        Object rep = executeCommand("TTL", key);
        return rep instanceof Long l ? l : -2L;
    }

    public synchronized long pttl(String key) throws IOException {
        Object rep = executeCommand("PTTL", key);
        return rep instanceof Long l ? l : -2L;
    }

    public synchronized long expire(String key, long seconds) throws IOException {
        Object rep = executeCommand("EXPIRE", key, String.valueOf(seconds));
        return rep instanceof Long l ? l : 0L;
    }

    public synchronized long publish(String channel, String msg) throws IOException {
        Object rep = executeCommand("PUBLISH", channel, msg);
        return rep instanceof Long l ? l : 0L;
    }

    public synchronized String hget(String key, String field) throws IOException {
        Object rep = executeCommand("HGET", key, field);
        if (rep == null) return null;
        if (rep instanceof byte[] bytes) return RespProtocol.toUtf8(bytes);
        return rep.toString();
    }

    public synchronized void hset(String key, String field, String val) throws IOException {
        executeCommand("HSET", key, field, val);
    }

    public synchronized void hset(String key, Map<String, String> hash) throws IOException {
        if (hash == null || hash.isEmpty()) return;
        List<String> cmd = new ArrayList<>(hash.size() * 2 + 2);
        cmd.add("HSET");
        cmd.add(key);
        for (Map.Entry<String, String> entry : hash.entrySet()) {
            cmd.add(entry.getKey());
            cmd.add(entry.getValue());
        }
        executeCommand(cmd.toArray(new String[0]));
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, String> hgetAll(String key) throws IOException {
        Object rep = executeCommand("HGETALL", key);
        if (!(rep instanceof List<?> list) || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < list.size() - 1; i += 2) {
            byte[] kBytes = (byte[]) list.get(i);
            byte[] vBytes = (byte[]) list.get(i + 1);
            if (kBytes != null) {
                map.put(RespProtocol.toUtf8(kBytes), RespProtocol.toUtf8(vBytes));
            }
        }
        return map;
    }

    public synchronized String scriptLoad(String script) throws IOException {
        Object rep = executeCommand("SCRIPT", "LOAD", script);
        if (rep instanceof String s) return s;
        return RespProtocol.toUtf8((byte[]) rep);
    }

    public synchronized Object eval(String script, List<String> keys, List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>(3 + keys.size() + args.size());
        cmd.add("EVAL");
        cmd.add(script);
        cmd.add(String.valueOf(keys.size()));
        cmd.addAll(keys);
        cmd.addAll(args);
        return executeCommand(cmd.toArray(new String[0]));
    }

    public synchronized Object evalsha(String sha1, List<String> keys, List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>(3 + keys.size() + args.size());
        cmd.add("EVALSHA");
        cmd.add(sha1);
        cmd.add(String.valueOf(keys.size()));
        cmd.addAll(keys);
        cmd.addAll(args);
        return executeCommand(cmd.toArray(new String[0]));
    }

    public static class ScanResult {
        private final String cursor;
        private final List<String> results;

        public ScanResult(String cursor, List<String> results) {
            this.cursor = cursor;
            this.results = results;
        }

        public String getCursor() { return cursor; }
        public List<String> getResult() { return results; }
    }

    @SuppressWarnings("unchecked")
    public synchronized ScanResult scan(String cursor, String pattern, int count) throws IOException {
        List<String> cmd = new ArrayList<>(6);
        cmd.add("SCAN");
        cmd.add(cursor != null ? cursor : "0");
        if (pattern != null) {
            cmd.add("MATCH");
            cmd.add(pattern);
        }
        if (count > 0) {
            cmd.add("COUNT");
            cmd.add(String.valueOf(count));
        }
        Object rep = executeCommand(cmd.toArray(new String[0]));
        if (!(rep instanceof List<?> list) || list.size() < 2) {
            return new ScanResult("0", Collections.emptyList());
        }
        String nextCursor = RespProtocol.toUtf8((byte[]) list.get(0));
        List<?> elements = (List<?>) list.get(1);
        List<String> items = new ArrayList<>(elements.size());
        for (Object el : elements) {
            if (el instanceof byte[] b) {
                items.add(RespProtocol.toUtf8(b));
            } else if (el != null) {
                items.add(el.toString());
            }
        }
        return new ScanResult(nextCursor, items);
    }

    public synchronized long llen(String key) throws IOException {
        Object rep = executeCommand("LLEN", key);
        return rep instanceof Long l ? l : 0L;
    }

    public boolean isBroken() {
        return broken || socket == null || socket.isClosed();
    }

    @Override
    public synchronized void close() {
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
