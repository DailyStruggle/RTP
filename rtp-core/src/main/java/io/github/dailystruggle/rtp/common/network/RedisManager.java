package io.github.dailystruggle.rtp.common.network;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RedisManager implements RTPNetworkManager {
    private final JedisPool pool;
    private final String rpcChannel = "rtp:rpc";

    // Package-private constructor for unit testing with a pre-built pool
    RedisManager(JedisPool pool) {
        this.pool = pool;
    }

    public RedisManager(String host, int port, String password) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);

        if (password != null && !password.isEmpty()) {
            this.pool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            this.pool = new JedisPool(poolConfig, host, port, 2000);
        }
    }

    @Override
    public void setLastTeleportTime(UUID playerId, long epochMillis) {
        try (Jedis jedis = pool.getResource()) {
            String key = "rtp:lastTp:" + playerId.toString();
            jedis.set(key, String.valueOf(epochMillis));
        }
    }

    @Override
    public long getLastTeleportTime(UUID playerId) {
        try (Jedis jedis = pool.getResource()) {
            String key = "rtp:lastTp:" + playerId.toString();
            String val = jedis.get(key);
            if (val == null || val.isEmpty()) return 0L;
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }

    @Override
    @Deprecated
    public void setCooldown(UUID playerId, long expirationTimeSeconds) {
        try (Jedis jedis = pool.getResource()) {
            String key = "rtp:cooldown:" + playerId.toString();
            jedis.setex(key, expirationTimeSeconds, "true");
        }
    }

    @Override
    @Deprecated
    public long getCooldown(UUID playerId) {
        try (Jedis jedis = pool.getResource()) {
            String key = "rtp:cooldown:" + playerId.toString();
            return jedis.ttl(key);
        }
    }

    @Override
    public void publish(String channel, String jsonPayload) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, jsonPayload);
        }
    }

    @Override
    public void initializeAsync() {
        CompletableFuture.runAsync(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        // Handle RPC message (to be implemented as needed)
                    }
                }, rpcChannel);
            } catch (Exception e) {
                io.github.dailystruggle.rtp.common.RTP.log(
                        java.util.logging.Level.WARNING, "Exception during Redis subscription to " + rpcChannel, e);
            }
        });
    }

    @Override
    public void shutdown() {
        if (pool != null) {
            pool.close();
        }
    }
}
