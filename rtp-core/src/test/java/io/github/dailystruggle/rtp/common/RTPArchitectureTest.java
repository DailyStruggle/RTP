package io.github.dailystruggle.rtp.common;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
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

    private static final Logger REPORT_LOG = Logger.getLogger(RTPArchitectureTest.class.getName());
    private static final String CORE_PACKAGE = "io.github.dailystruggle.rtp.common..";
    private static final String API_PACKAGE = "io.github.dailystruggle.rtp.api..";

    /**
     * Rule 1 - Platform Decoupling (core and api).
     *
     * <p>Classes in {@code rtp-core} and {@code rtp-api} must remain strictly
     * platform-agnostic and must never import Bukkit, Paper, Moonrise, Minecraft, or Fabric types.
     */
    @ArchTest
    static final ArchRule core_and_api_must_not_depend_on_platform_apis =
            noClasses()
                    .that().resideInAnyPackage(CORE_PACKAGE, API_PACKAGE)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.bukkit..",
                            "io.papermc.paper..",
                            "ca.spottedleaf.moonrise..",
                            "net.minecraft..",
                            "net.fabricmc.."
                    )
                    .because("The rtp-core and rtp-api modules must remain platform-agnostic and may only "
                            + "depend on rtp-api interfaces, never on Bukkit, Paper, Moonrise, Minecraft, or Fabric.");

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
                    .and().haveSimpleNameNotContaining("TestChunkTicketCmd")
                    .and().haveSimpleNameNotContaining("TestEventsCmd")
                    // Bounded per-mock await on the async worker that runs the BIT check (ADR-121).
                    .and().haveSimpleNameNotContaining("TestTeleportCmd")
                    .and().haveSimpleNameNotContaining("NetworkModeBootstrap")
                    .and().haveSimpleNameNotContaining("NetworkStateBinding")
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
                    // around RTPRuntime.scheduler() in the core RTP() constructor; the thread-isolation
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

    /**
     * Rule 7 - Thread Management / Carve-Outs.
     *
     * <p>Backend plugin JVMs must schedule all periodic, delayed, or async work through
     * {@link RTPScheduler}. Raw threads and thread pools (e.g. {@code new Thread()},
     * {@code Executors.new*}) must not be created outside documented carve-outs
     * (e.g. {@code AnvilIoPool}, test harnesses).
     */
    @ArchTest
    static final ArchRule no_thread_instantiation_or_executors_outside_carveouts =
            noClasses()
                    .that().resideInAnyPackage(CORE_PACKAGE, API_PACKAGE)
                    .and().resideOutsideOfPackage("..mock..")
                    .should().callConstructorWhere(
                            target(owner(assignableTo(Thread.class)))
                    )
                    .orShould().callMethodWhere(
                            target(owner(assignableTo(java.util.concurrent.Executors.class)))
                                    .and(target(nameMatching("new.*ThreadPool|new.*Executor")))
                    )
                    .because("Backend plugin tasks must be scheduled through RTPScheduler. "
                            + "Raw Thread construction and Executors thread-pool creation outside "
                            + "documented carve-outs are prohibited.");

    /**
     * Rule 8 - Logging Discipline (No printStackTrace).
     *
     * <p>Stack traces must be routed through {@code RTPRuntime.log(Level.WARNING, ...)}
     * and never dumped via {@link Throwable#printStackTrace()}.
     */
    @ArchTest
    static final ArchRule no_print_stack_trace_in_core_or_api =
            noClasses()
                    .that().resideInAnyPackage(CORE_PACKAGE, API_PACKAGE)
                    .should().callMethodWhere(
                            target(owner(assignableTo(Throwable.class)))
                                    .and(target(nameMatching("printStackTrace")))
                    )
                    .because("Zero printStackTrace() - all exceptions must be logged via "
                            + "RTP.log(Level.WARNING, \"msg\", e).");

    /**
     * Rule 9 - Console and Platform Logger Isolation (No System.out / Bukkit.getLogger).
     *
     * <p>Logging must go through {@code RTPRuntime.log()} / {@code RTPServerAccessor.log()}.
     * Direct stdout/stderr console writes and direct {@code Bukkit.getLogger()} calls are prohibited.
     */
    @ArchTest
    static final ArchRule no_system_out_or_bukkit_getlogger_in_core_or_api =
            noClasses()
                    .that().resideInAnyPackage(CORE_PACKAGE, API_PACKAGE)
                    .should().accessFieldWhere(
                            target(owner(assignableTo(System.class)))
                                    .and(target(nameMatching("out|err")))
                    )
                    .orShould().callMethodWhere(
                            target(owner(assignableTo(System.class)))
                                    .and(target(nameMatching("setOut|setErr")))
                    )
                    .because("Direct console output (System.out / System.err) and Bukkit.getLogger() "
                            + "are forbidden in core and api; use RTP.log() instead.");

    /**
     * Rule 10 - Synchronous Chunk I/O (S-005).
     *
     * <p>Core and api code must never invoke synchronous chunk loading methods on native
     * platform worlds (such as {@code org.bukkit.World#getChunkAt(int, int)}). All chunk
     * retrieval must proceed asynchronously via {@link RTPWorld#getChunkAt(int, int)} or
     * {@link RTPWorld#getChunkAtAsync(int, int)}.
     */
    @ArchTest
    static final ArchRule no_synchronous_chunk_io_on_platform_world =
            noClasses()
                    .that().resideInAnyPackage(CORE_PACKAGE, API_PACKAGE)
                    .should().callMethodWhere(
                            target(nameMatching("getChunkAt"))
                                    .and(target(owner(nameMatching(".*org\\.bukkit\\.World.*|.*net\\.minecraft.*"))))
                    )
                    .because("REQ-RTP-S-005: Zero synchronous chunk loading on main threads. "
                            + "All chunk operations must go through RTPWorld's async methods.");

    /**
     * Rule 11 - Package Cycle Report (ADR-115, report-only).
     *
     * <p>Logs whether the {@code rtp.common} package slices are cycle-free without failing
     * the build while the tangle remains. Promote to an enforced {@code @ArchTest} rule once
     * the cycle count reaches zero.
     */
    @ArchTest
    static void report_rtp_common_package_cycles(JavaClasses classes) {
        ArchRule rule = SlicesRuleDefinition.slices()
                .matching("io.github.dailystruggle.rtp.common.(*)..")
                .should().beFreeOfCycles();
        try {
            rule.check(classes);
            REPORT_LOG.info("[ADR-115] rtp.common package slices are cycle-free.");
        } catch (AssertionError e) {
            REPORT_LOG.info("[ADR-115 report-only] " + e.getMessage());
        }
    }

    /**
     * Rule 12 - Command Sub-Package Cycles (ADR-115, enforced).
     *
     * <p>Enforces that the {@code rtp.common.commands} feature sub-packages are cycle-free.
     * Feature packages depend downwards on {@code commands.base}, not cyclically on each other.
     */
    @ArchTest
    static final ArchRule rtp_commands_subpackages_must_be_free_of_cycles =
            SlicesRuleDefinition.slices()
                    .matching("io.github.dailystruggle.rtp.common.commands.(*)..")
                    .should().beFreeOfCycles()
                    .because("ADR-115 Seam 2: Command feature sub-packages must not have cyclic dependencies.");

    /**
     * Rule 13 - Configuration Does Not Drive Tasks or Regions (ADR-115, RTP-68).
     *
     * <p>{@code configuration} loads parsers only. Task shutdown, teleport cancellation,
     * tick-budget refresh and region rebuild on reload run in
     * {@code lifecycle.ConfigReloadCoordinator}, reached through {@code ConfigReloadLifecycle}.
     */
    @ArchTest
    static final ArchRule configuration_must_not_depend_on_tasks_or_regions =
            noClasses()
                    .that().resideInAPackage("io.github.dailystruggle.rtp.common.configuration..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "io.github.dailystruggle.rtp.common.tasks..",
                            "io.github.dailystruggle.rtp.common.selection.region",
                            "io.github.dailystruggle.rtp.common.lifecycle.."
                    )
                    .because("ADR-115: configuration is a lower tier than tasks and regions; reload "
                            + "orchestration belongs to the Tier 4 ConfigReloadCoordinator.");

    /**
     * Rule 14 - Lifecycle Is Tier 4 (ADR-115).
     *
     * <p>Only the composition root ({@code common.RTP}) installs the reload coordinator.
     */
    @ArchTest
    static final ArchRule only_composition_root_depends_on_lifecycle =
            noClasses()
                    .that().resideInAPackage(CORE_PACKAGE)
                    .and().resideOutsideOfPackages(
                            "io.github.dailystruggle.rtp.common",
                            "io.github.dailystruggle.rtp.common.lifecycle..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("io.github.dailystruggle.rtp.common.lifecycle..")
                    .because("ADR-115 Invariant 2: no lower-tier package depends on Tier 4.");

    /**
     * Rule 15 - Regions Do Not Create Pipeline Tasks (ADR-115, RTP-68).
     *
     * <p>{@code selection.region} starts scans and teleports through the
     * {@code RegionTaskDispatcher} it owns; the Tier 4 {@code lifecycle.RegionTaskCoordinator}
     * creates the {@code ScanTask} and {@code TeleportPipelineTask} instances.
     */
    @ArchTest
    static final ArchRule regions_must_not_depend_on_scan_or_teleport_tasks =
            noClasses()
                    .that().resideInAPackage("io.github.dailystruggle.rtp.common.selection.region..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "io.github.dailystruggle.rtp.common.tasks.scan..",
                            "io.github.dailystruggle.rtp.common.tasks.teleport.."
                    )
                    .because("ADR-115 seam 3: scan and teleport tasks import Region, so Region "
                            + "dispatches them through RegionTaskDispatcher.");

    /**
     * Rule 16 - Base Task Types Stay Below Regions (ADR-115, RTP-68).
     *
     * <p>{@code common.tasks} holds only the base types regions use ({@code RTPTaskPipe},
     * {@code CompletionPaced}, {@code RTPRunnable}); tasks that read regions live in
     * {@code tasks.scan} and {@code tasks.teleport}.
     */
    @ArchTest
    static final ArchRule base_tasks_must_not_depend_on_selection =
            noClasses()
                    .that().resideInAPackage("io.github.dailystruggle.rtp.common.tasks")
                    .should().dependOnClassesThat()
                    .resideInAPackage("io.github.dailystruggle.rtp.common.selection..")
                    .because("ADR-115 seam 3: selection.region depends on common.tasks, so the "
                            + "base task package must not depend back on it.");

    /**
     * Rule 17 - Spatial Memory Stays Below Regions (ADR-115, RTP-67).
     *
     * <p>{@code Region} owns its {@code MemoryShape}, so shapes, TTL policy and residency must
     * not reach back into the classes of {@code selection.region}. Shared value types they need
     * ({@code FailTypes}, {@code BiomeNames}) live in {@code selection.model}; {@code SubspaceShape}
     * wraps a {@code Region} and so lives in {@code selection.region}.
     */
    @ArchTest
    static final ArchRule spatial_memory_must_not_depend_on_region_classes =
            noClasses()
                    .that().resideInAPackage(
                            "io.github.dailystruggle.rtp.common.selection.region.selectors.memory..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("io.github.dailystruggle.rtp.common.selection.region")
                    .because("ADR-115: selection.region depends on spatial memory, so memory "
                            + "must not depend back on it.");

    /**
     * Rule 18 - Selection Model Is Tier 1 (ADR-115, RTP-67).
     *
     * <p>{@code selection.model} holds value types shared by shapes, regions and diagnostics, and
     * depends on no other {@code rtp-core} package.
     */
    @ArchTest
    static final ArchRule selection_model_must_not_depend_on_core =
            noClasses()
                    .that().resideInAPackage("io.github.dailystruggle.rtp.common.selection.model..")
                    .should().dependOnClassesThat(
                            JavaClass.Predicates.resideInAPackage(CORE_PACKAGE).and(
                                    JavaClass.Predicates.resideOutsideOfPackage(
                                            "io.github.dailystruggle.rtp.common.selection.model..")))
                    .because("ADR-115 Tier 1: shared selection value types have no core dependencies.");

    /**
     * Rule 19 - Tools Stay Below Regions (ADR-115, RTP-67).
     *
     * <p>Regions use the trackers, health gates and diagnostics in {@code tools}. The
     * {@code MemoryTracker} census reads regions through {@code tools.TicketCensusRegion} views,
     * and {@code PlaceholderProvider}, which renders region stats, lives in {@code placeholders}.
     */
    @ArchTest
    static final ArchRule tools_must_not_depend_on_region_classes =
            noClasses()
                    .that().resideInAPackage("io.github.dailystruggle.rtp.common.tools..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("io.github.dailystruggle.rtp.common.selection.region")
                    .because("ADR-115: selection.region depends on tools, so tools must not "
                            + "depend back on it.");

    /**
     * Rule 20 - Runtime Holder Is Tier 1 (ADR-115 seam 4).
     *
     * <p>{@code common.runtime} holds the platform accessor, scheduler, economy and the bootstrap
     * log buffer that every core package reads, typed only by {@code rtp-api}. A core dependency
     * here would close a cycle with each package that logs.
     */
    @ArchTest
    static final ArchRule runtime_holder_must_not_depend_on_core =
            noClasses()
                    .that().resideInAPackage("io.github.dailystruggle.rtp.common.runtime..")
                    .should().dependOnClassesThat(
                            JavaClass.Predicates.resideInAPackage(CORE_PACKAGE).and(
                                    JavaClass.Predicates.resideOutsideOfPackage(
                                            "io.github.dailystruggle.rtp.common.runtime..")))
                    .because("ADR-115 seam 4: the runtime holder is Tier 1; the composition root "
                            + "injects config-dependent behaviour such as the log buffer gate.");
}
