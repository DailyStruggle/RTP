package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Shared, Docker-gated Redis container for the {@code transport/redis} integration
 * tier (ENTERPRISE_READINESS.md item 17: "Use Testcontainers Redis, not mocks").
 *
 * <p>A single {@code redis:7-alpine} container is started lazily on first use and
 * reused across every Redis integration test in this package (the Testcontainers
 * singleton-container pattern). It is never explicitly stopped; the Ryuk reaper
 * cleans it up when the test JVM exits.</p>
 *
 * <p>Every container-backed test class gates itself with
 * {@code @EnabledIf("io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisTestContainer#dockerAvailable")}
 * so a routine build on a box without a Docker daemon skips the tier cleanly
 * rather than failing. {@link #dockerAvailable()} deliberately does <em>not</em>
 * touch {@link #instance()} so the probe never starts a container on a
 * Docker-less host.</p>
 */
final class RedisTestContainer {

    private static final DockerImageName IMAGE = DockerImageName.parse("redis:7-alpine");
    private static final int REDIS_PORT = 6379;

    private static volatile GenericContainer<?> container;

    private RedisTestContainer() {
    }

    /**
     * @return {@code true} when a usable Docker daemon is reachable. Used as the
     * {@code @EnabledIf} predicate for the Redis integration tests.
     */
    @SuppressWarnings("unused") // referenced by @EnabledIf via reflection
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized GenericContainer<?> instance() {
        GenericContainer<?> c = container;
        if (c == null) {
            c = new GenericContainer<>(IMAGE).withExposedPorts(REDIS_PORT);
            c.start();
            container = c;
        }
        return c;
    }

    static String host() {
        return instance().getHost();
    }

    static int port() {
        return instance().getFirstMappedPort();
    }

    /**
     * @return a fresh {@link JedisPool} bound to the shared container. The caller
     * owns the pool and must {@code close()} it.
     */
    static JedisPool newPool() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(8);
        cfg.setMaxIdle(4);
        return new JedisPool(cfg, host(), port(), 2000);
    }
}
