package io.github.dailystruggle.rtp.common.database.options;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared, Docker-gated MySQL and PostgreSQL containers for the
 * {@code database/options} integration tier (ENTERPRISE_READINESS.md item 18:
 * drive the real {@code MySQLDatabaseAccessor} / {@code PostgreSQLDatabaseAccessor}
 * against real servers instead of copied {@code Testable*} subclasses).
 *
 * <p>A single container per engine is started lazily on first use and reused across
 * every test in this package (the Testcontainers singleton-container pattern). They
 * are never explicitly stopped; the Ryuk reaper cleans them up when the test JVM
 * exits.</p>
 *
 * <p>Every container-backed test class gates itself with
 * {@code @EnabledIf("io.github.dailystruggle.rtp.common.database.options.SqlTestContainers#dockerAvailable")}
 * so a routine build on a box without a Docker daemon skips the tier cleanly rather
 * than failing. {@link #dockerAvailable()} deliberately does <em>not</em> start a
 * container so the probe never touches Docker on a Docker-less host.</p>
 */
final class SqlTestContainers {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4");
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

    private static volatile MySQLContainer<?> mysql;
    private static volatile PostgreSQLContainer<?> postgres;

    private SqlTestContainers() {
    }

    /**
     * @return {@code true} when a usable Docker daemon is reachable. Used as the
     * {@code @EnabledIf} predicate for the container-backed tests.
     */
    @SuppressWarnings("unused") // referenced by @EnabledIf via reflection
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    static synchronized MySQLContainer<?> mysql() {
        MySQLContainer<?> c = mysql;
        if (c == null) {
            c = new MySQLContainer<>(MYSQL_IMAGE);
            c.start();
            mysql = c;
        }
        return c;
    }

    static synchronized PostgreSQLContainer<?> postgres() {
        PostgreSQLContainer<?> c = postgres;
        if (c == null) {
            c = new PostgreSQLContainer<>(POSTGRES_IMAGE);
            c.start();
            postgres = c;
        }
        return c;
    }
}
