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
     * Rule 1 – Platform Decoupling.
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
     * Rule 2 – Non-Blocking Execution.
     *
     * <p>No class in the core or api packages is permitted to call the synchronous
     * {@link CompletableFuture#get()} or {@link CompletableFuture#join()} methods.
     * All futures must be resolved asynchronously (e.g. via {@code thenApply}, {@code thenAccept}).
     *
     * <p>The classes below are individually documented ARCH-EXCEPTIONs. Each one calls
     * {@code .get()} or {@code .join()} on a future that is guaranteed by the platform
     * adapter or by the surrounding protocol to already be resolved at the call site.
     * In unit tests, {@code MockRTPWorld}, {@code MockLocationGenerator}, and
     * {@code MockRTPScheduler} return {@link CompletableFuture#completedFuture} values
     * (including a pre-completed chunk future keyed {@code 0L}), so none of these calls
     * block under test. Any new exception must be added here with the same level of detail.
     *
     * <ul>
     *   <li><b>LocationGenerator</b> – {@code world.getChunkAtAsync(...).get()} and
     *       {@code ticket.chunks().getFirst().get()} (chunk-load futures completed by the
     *       platform adapter); {@code locationFuture.join()} (future returned by
     *       {@link io.github.dailystruggle.rtp.api.selection.ILocationGenerator}, always
     *       pre-completed by {@code MockLocationGenerator} in tests).
     *       Timed overloads ({@code get(5, SECONDS)}) carry a hard deadline.</li>
     *   <li><b>RegionCacheTask</b> – {@code .join()} at line 74 on a future that is
     *       submitted and completed by the same scheduler tick before the task reads it.</li>
     *   <li><b>RegionQueueManager</b> – {@code .get()} at line 197 during {@code shutDown()},
     *       called only on the shutdown path after the producing threads have been
     *       signalled to stop; the future is guaranteed to complete within the shutdown
     *       timeout window.</li>
     *   <li><b>ScanTask</b> – {@code .join()} at line 572 inside {@code testPos()}, which
     *       runs on an async worker thread (never the main thread); the future wraps a
     *       synchronous chunk-presence check that completes immediately on the adapter.</li>
     *   <li><b>TeleportPipelineTask</b> – {@code .join()} at line 188 in {@code runSetup()},
     *       executed on the platform's async scheduler thread after the chunk-load future
     *       has been primed by the preceding pipeline stage.</li>
     *   <li><b>MemoryTracker</b> – {@code .join()} at line 207 in {@code runDiagnostics()},
     *       which is a low-frequency diagnostic path; the future resolves against a
     *       completed server-forced-count query provided by the adapter.</li>
     *   <li><b>PlaceholderProvider</b> – {@code .join()} at lines 182 and 587 inside a
     *       static initialiser that runs once during class loading on a background thread;
     *       the futures wrap synchronous lookups that complete before the initialiser
     *       returns.</li>
     *   <li><b>ChunkReservation</b> – {@code .get(timeout, unit)} inside
     *       {@link io.github.dailystruggle.rtp.api.world.ChunkReservation#awaitReady(long, java.util.concurrent.TimeUnit)}.
     *       ADR-015 (Paper chunk-system-v2 follow-up — ticket-application race): the future
     *       is completed by the platform adapter once {@code addPluginChunkTicket} has been
     *       applied on the primary/region thread. The bounded wait (2s) is REQ-RTP-S-005-
     *       compliant because it is only ever called off-tick (the location generator runs
     *       on an async scheduler thread); a timeout attributes to
     *       {@code FailTypes.timeout / reason=ticketApplyTimeout}. The method lives in
     *       {@code rtp-api} so it cannot be covered by the {@code LocationGenerator} name-
     *       match; it is exempted here explicitly.</li>
     * </ul>
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
                    .should().callMethodWhere(
                            target(owner(assignableTo(CompletableFuture.class)))
                                    .and(target(nameMatching("get|join")))
                    )
                    .because("CompletableFuture.get() and .join() block the calling thread. "
                            + "Core and api code must handle futures asynchronously. "
                            + "Any new exception must be documented in the Javadoc above.");

    /**
     * Rule 3 – Driver Isolation.
     *
     * <p>Network-backend driver classes ({@code redis.clients.*}, {@code org.postgresql.*})
     * are optional runtime dependencies that must not leak into general core code.
     * Only the two designated accessor classes are permitted to reference them directly:
     * <ul>
     *   <li>{@code RedisManager} — sole owner of all Jedis instantiation.</li>
     *   <li>{@code PostgreSQLDatabaseAccessor} — sole owner of the PostgreSQL JDBC URL;
     *       it uses only standard {@code DriverManager} so this rule is a forward guard
     *       against accidental driver-class imports being added in future.</li>
     * </ul>
     * Any new direct dependency on a network driver must be isolated to a dedicated
     * accessor class and listed here.
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
     * Rule 4 – Thread Yielding / Scheduler Isolation.
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
                    .should().resideInAPackage(CORE_PACKAGE)
                    .allowEmptyShould(true) // platform modules are separate Gradle subprojects
                    .because("RTPScheduler implementations are platform-specific and must be "
                            + "isolated to the spigot, paper, or folia modules, not rtp-core.");

    /**
     * Rule 5 – Chunk Ticket Allocation Boundary (Subsystem 1).
     *
     * <p>{@link RTPWorld#setForceLoaded} is the project's ticket-allocation gate:
     * calling it with {@code true} is equivalent to {@code malloc} and with
     * {@code false} is equivalent to {@code free}.  The only classes permitted to
     * call it are:
     * <ul>
     *   <li>{@link ChunkReservation} – the sole legitimate lifecycle owner that
     *       guarantees a matching {@code free} via {@link ChunkReservation#close()}.</li>
     *   <li>Any {@link RTPWorld} subclass – platform adapters that override
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
     * Rule 6 – Raw Platform Ticket Call Isolation (Subsystem 1, adapter layer).
     *
     * <p>{@code setForceLoadedImpl} is the abstract hook that platform adapters
     * implement to call the raw Bukkit/Folia/Paper {@code addPluginChunkTicket} /
     * {@code setForceLoaded} APIs.  It must only be dispatched through the
     * reference-counted gate in {@link RTPWorld#setForceLoaded} and
     * {@link RTPWorld#refreshForceLoaded}; calling it directly from any other
     * context bypasses the ticket counter and leaks tickets.
     *
     * <p>Permitted callers: {@link RTPWorld} itself (the two final public methods
     * that own the counter).  All other classes — including the adapter subclasses
     * that <em>implement</em> the method — must not call it directly.
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
