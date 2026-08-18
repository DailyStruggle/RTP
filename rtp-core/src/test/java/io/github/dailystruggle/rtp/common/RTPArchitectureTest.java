package io.github.dailystruggle.rtp.common;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.util.concurrent.CompletableFuture;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** ArchUnit gatekeeper: platform decoupling, non-blocking core/api,
 *  driver isolation, scheduler isolation, chunk-ticket allocation boundary,
 *  raw-platform-ticket isolation. Each rule below documents its rationale. */
@AnalyzeClasses(
        packages = "io.github.dailystruggle.rtp",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class RTPArchitectureTest {

    private static final String CORE_PACKAGE = "io.github.dailystruggle.rtp.common..";
    private static final String API_PACKAGE = "io.github.dailystruggle.rtp.api..";

    /**
     * Rule 1 - Platform Decoupling.
     *
     * <p>Classes in the {@code rtp.common} core package must remain strictly
     * platform-agnostic and must never import Bukkit, Paper, or Moonrise types.
     */
    @ArchTest
    static final ArchRule core_must_not_depend_on_platform_apis =
            noClasses()
                    .that().resideInAPackage(CORE_PACKAGE)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.bukkit..",
                            "io.papermc.paper..",
                            "ca.spottedleaf.moonrise.."
                    )
                    .because("The rtp-core module must remain platform-agnostic and may only "
                            + "depend on rtp-api interfaces, never on Bukkit, Paper, or Moonrise.");

    /**
     * Rule 2 - Non-Blocking Execution.
     * Core and API classes must resolve CompletableFutures asynchronously without blocking calls.
     * Listed exclusions represent documented off-tick, bounded, or pre-completed exceptions.
     */
    @ArchTest
    static final ArchRule no_blocking_future_calls_in_core_or_api =
            noClasses()
                    .that().resideInAnyPackage(CORE_PACKAGE, API_PACKAGE)
                    .and().haveSimpleNameNotContaining("LocationGenerator")
                    .and().haveSimpleNameNotContaining("RegionCacheTask")
                    .and().haveSimpleNameNotContaining("RegionQueueManager")
                    .and().haveSimpleNameNotContaining("ScanTask")
                    .and().haveSimpleNameNotContaining("TeleportPipelineTask")
                    .and().haveSimpleNameNotContaining("MemoryTracker")
                    .and().haveSimpleNameNotContaining("PlaceholderProvider")
                    .and().haveSimpleNameNotContaining("ChunkReservation")
                    .and().haveSimpleNameNotContaining("TestSchedulerCmd")
                    .and().haveSimpleNameNotContaining("NetworkModeBootstrap")
                    .should().callMethodWhere(
                            target(owner(assignableTo(CompletableFuture.class)))
                                    .and(target(nameMatching("get|join")))
                    )
                    .because("CompletableFuture.get() and .join() block the calling thread. "
                            + "Core and api code must handle futures asynchronously. "
                            + "Any new exception must be documented in the Javadoc above.");

    /**
     * Rule 3 - Driver Isolation.
     * Direct network driver dependencies (Jedis, PostgreSQL JDBC) must remain confined
     * to designated accessor classes (RedisManager, PostgreSQLDatabaseAccessor).
     */
    @ArchTest
    static final ArchRule network_driver_deps_must_be_isolated =
            noClasses()
                    .that().resideInAPackage(CORE_PACKAGE)
                    // Use full-name matching to also exclude anonymous/inner classes such as
                    // RedisManager$1 (the JedisPubSub subclass) whose simple name is just "$1".
                    .and().haveNameNotMatching(".*RedisManager.*")
                    .and().haveNameNotMatching(".*PostgreSQLDatabaseAccessor.*")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "redis.clients..",
                            "org.postgresql.."
                    )
                    .allowEmptyShould(true)
                    .because("Network driver dependencies (Jedis, PostgreSQL JDBC) must be "
                            + "confined to their designated accessor classes (RedisManager, "
                            + "PostgreSQLDatabaseAccessor) and their inner/anonymous classes. "
                            + "All other core code must remain driver-agnostic.");

    /**
     * Rule 4 - Thread Yielding / Scheduler Isolation.
     *
     * <p>Implementations of {@link RTPScheduler} must live exclusively in
     * platform-specific modules (spigot, paper, folia) and must never be placed
     * inside the {@code rtp.common} core package.
     */
    @ArchTest
    static final ArchRule scheduler_implementations_must_not_reside_in_core =
            noClasses()
                    .that().implement(RTPScheduler.class)
                    // Exclude the test-fixtures mock package: MockRTPScheduler is a deliberately
                    // minimal in-memory stand-in used only in unit tests and is never deployed
                    // to a live server.  It lives in testFixtures (not src/main), so ArchUnit
                    // would otherwise flag it as a production violation.
                    .and().resideOutsideOfPackage("..mock..")
                    // Exclude ProfilingRTPScheduler: it is NOT a platform scheduler implementation
                    // (it creates no threads and owns no platform API), but a platform-neutral
                    // delegating decorator that times the RTP-submitted tasks it forwards to the
                    // real platform scheduler. It must live in core because it is installed
                    // around RTP.scheduler in the core RTP() constructor; the thread-isolation
                    // intent of this rule is not weakened because all threading still belongs to
                    // the wrapped platform scheduler.
                    .and().doNotHaveFullyQualifiedName(
                            "io.github.dailystruggle.rtp.common.metrics.ProfilingRTPScheduler")
                    .should().resideInAPackage(CORE_PACKAGE)
                    .allowEmptyShould(true) // platform modules are separate Gradle subprojects
                    .because("RTPScheduler implementations are platform-specific and must be "
                            + "isolated to the spigot, paper, or folia modules, not rtp-core. "
                            + "The sole core-resident exception is the non-thread-creating "
                            + "ProfilingRTPScheduler decorator, excluded by name above.");

    /**
     * Rule 5 - Chunk Ticket Allocation Boundary (Subsystem 1).
     *
     * <p>{@link RTPWorld#setForceLoaded} is the project's ticket-allocation gate:
     * calling it with {@code true} is equivalent to {@code malloc} and with
     * {@code false} is equivalent to {@code free}.  The only classes permitted to
     * call it are:
     * <ul>
     *   <li>{@link ChunkReservation} - the sole legitimate lifecycle owner that
     *       guarantees a matching {@code free} via {@link ChunkReservation#close()}.</li>
     *   <li>Any {@link RTPWorld} subclass - platform adapters that override
     *       {@code keepChunkAt}/{@code forgetChunkAt} and delegate internally to
     *       {@code setForceLoaded} as part of the world's own ticket-map management.</li>
     * </ul>
     *
     * <p>Any other caller is a "naked ticket": a raw {@code malloc} with no
     * guaranteed matching {@code free}, which will permanently force-load chunks
     * and exhaust server RAM (hazard H-004).
     *
     * <p><b>ARCH-EXCEPTION policy:</b> Do not add exclusions here.  If a new class
     * genuinely needs to manage chunk tickets, it must do so by constructing a
     * {@code ChunkReservation} in a {@code try-with-resources} block.
     */
    @ArchTest
    static final ArchRule only_ChunkReservation_and_RTPWorld_may_call_setForceLoaded =
            noClasses()
                    .that()
                    // ChunkReservation is the designated lifecycle owner.
                    .doNotHaveFullyQualifiedName(ChunkReservation.class.getName())
                    // RTPWorld subclasses (platform adapters) manage the internal ticket map
                    // via keepChunkAt/forgetChunkAt, which delegate to setForceLoaded.
                    .and().areNotAssignableTo(RTPWorld.class)
                    .should().callMethodWhere(
                            target(owner(assignableTo(RTPWorld.class)))
                                    .and(target(nameMatching("setForceLoaded")))
                    )
                    .allowEmptyShould(true)
                    .because("setForceLoaded(true) is malloc and setForceLoaded(false) is free. "
                            + "All ticket allocation must flow through ChunkReservation so that "
                            + "close() guarantees the matching free() on every code path, "
                            + "preventing the permanent force-load leak described in hazard H-004.");

    /**
     * Rule 6 - Raw Platform Ticket Call Isolation (Subsystem 1, adapter layer).
     *
     * <p>{@code setForceLoadedImpl} is the abstract hook that platform adapters
     * implement to call the raw Bukkit/Folia/Paper {@code addPluginChunkTicket} /
     * {@code setForceLoaded} APIs.  It must only be dispatched through the
     * reference-counted gate in {@link RTPWorld#setForceLoaded} and
     * {@link RTPWorld#refreshForceLoaded}; calling it directly from any other
     * context bypasses the ticket counter and leaks tickets.
     *
     * <p>Permitted callers: {@link RTPWorld} itself (the two final public methods
     * that own the counter).  All other classes - including the adapter subclasses
     * that <em>implement</em> the method - must not call it directly.
     */
    @ArchTest
    static final ArchRule only_RTPWorld_may_call_setForceLoadedImpl =
            noClasses()
                    .that()
                    .doNotHaveFullyQualifiedName(RTPWorld.class.getName())
                    .should().callMethodWhere(
                            target(owner(assignableTo(RTPWorld.class)))
                                    .and(target(nameMatching("setForceLoadedImpl")))
                    )
                    .allowEmptyShould(true)
                    .because("setForceLoadedImpl is the raw addPluginChunkTicket call. "
                            + "Bypassing RTPWorld.setForceLoaded skips the reference-count "
                            + "guard and leaks chunk tickets permanently.");
}
