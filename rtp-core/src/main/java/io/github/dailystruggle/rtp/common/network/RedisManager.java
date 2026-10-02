package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespConnection;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespPool;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespPubSub;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RedisManager implements RTPNetworkManager {
    private final RespPool pool;
    private final String rpcChannel = "rtp:rpc";

    // Package-private constructor for unit testing with a pre-built pool
    RedisManager(RespPool pool) {
        this.pool = pool;
    }

    public RedisManager(String host, int port, String password) {
        this.pool = new RespPool(host, port, 2000, password, 16);
    }

    @Override
    public void setLastTeleportTime(UUID playerId, long epochMillis) {
        try (RespConnection jedis = pool.getResource()) {
            String key = "rtp:lastTp:" + playerId.toString();
            jedis.set(key, String.valueOf(epochMillis));
        } catch (Exception e) {
            io.github.dailystruggle.rtp.common.RTP.log(
                    java.util.logging.Level.WARNING, "Failed to setLastTeleportTime in Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public long getLastTeleportTime(UUID playerId) {
        try (RespConnection jedis = pool.getResource()) {
            String key = "rtp:lastTp:" + playerId.toString();
            String val = jedis.get(key);
            if (val == null || val.isEmpty()) return 0L;
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException e) {
                return 0L;
            }
        } catch (Exception e) {
            io.github.dailystruggle.rtp.common.RTP.log(
                    java.util.logging.Level.WARNING, "Failed to getLastTeleportTime from Redis: " + e.getMessage(), e);
            return 0L;
        }
    }

    @Override
    @Deprecated
    public void setCooldown(UUID playerId, long expirationTimeSeconds) {
        try (RespConnection jedis = pool.getResource()) {
            String key = "rtp:cooldown:" + playerId.toString();
            jedis.setex(key, expirationTimeSeconds, "true");
        } catch (Exception e) {
            io.github.dailystruggle.rtp.common.RTP.log(
                    java.util.logging.Level.WARNING, "Failed to setCooldown in Redis: " + e.getMessage(), e);
        }
    }

    @Override
    @Deprecated
    public long getCooldown(UUID playerId) {
        try (RespConnection jedis = pool.getResource()) {
            String key = "rtp:cooldown:" + playerId.toString();
            return jedis.ttl(key);
        } catch (Exception e) {
            io.github.dailystruggle.rtp.common.RTP.log(
                    java.util.logging.Level.WARNING, "Failed to getCooldown from Redis: " + e.getMessage(), e);
            return -2L;
        }
    }

    @Override
    public void publish(String channel, String jsonPayload) {
        try (RespConnection jedis = pool.getResource()) {
            jedis.publish(channel, jsonPayload);
        } catch (Exception e) {
            io.github.dailystruggle.rtp.common.RTP.log(
                    java.util.logging.Level.WARNING, "Failed to publish in Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public void initializeAsync() {
        CompletableFuture.runAsync(() -> {
            try (RespConnection jedis = pool.getResource()) {
                RespPubSub pubSub = new RespPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        // Handle RPC message (to be implemented as needed)
                    }
                };
                pubSub.proceed(jedis, rpcChannel);
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
