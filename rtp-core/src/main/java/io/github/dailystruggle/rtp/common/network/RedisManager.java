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
    public void setCooldown(UUID playerId, long expirationTimeSeconds) {
        try (Jedis jedis = pool.getResource()) {
            String key = "rtp:cooldown:" + playerId.toString();
            jedis.setex(key, expirationTimeSeconds, "true");
        }
    }

    @Override
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
                e.printStackTrace();
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
