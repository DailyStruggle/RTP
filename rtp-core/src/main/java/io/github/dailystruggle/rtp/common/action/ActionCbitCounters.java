package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.CbitCounter;
import io.github.dailystruggle.bit.GateOutcomeCounter;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lock-free continuous health counters for the action engine (ADR-121 section 5). */
public final class ActionCbitCounters {
  public static final int DEFAULT_GATE_OUTCOME_SAMPLES = 200;

  private static final int MAX_TRACKED_OBJECTIVES = 128;
  private static final int MAX_TRACKED_GATES = 512;
  private static final String OBJECTIVE_OVERFLOW = "<overflow>";
  private static final Pattern UNRESOLVED_TOKEN = Pattern.compile("\\[[A-Za-z_]\\w*]");

  public static final BitCode OBJECTIVE_MISSING = RtpBitCodes.ACTION.register(2101,
      "ACTION_CBIT_OBJECTIVE_MISSING", Level.WARNING, BitTier.CBIT, "action", "",
      "Create the external scoreboard objective or correct its name.");
  public static final BitCode GATE_NEVER_PASSED = RtpBitCodes.ACTION.register(2102,
      "ACTION_CBIT_GATE_NEVER_PASSED", Level.WARNING, BitTier.CBIT, "action", "",
      "Check the gate expression and the runtime values it reads.");
  public static final BitCode GATE_ALWAYS_PASSED = RtpBitCodes.ACTION.register(2103,
      "ACTION_CBIT_GATE_ALWAYS_PASSED", Level.INFO, BitTier.CBIT, "action", "",
      "Confirm that the gate is intended to pass for every observed evaluation.");
  public static final BitCode PLACEHOLDER_UNRESOLVED = RtpBitCodes.ACTION.register(2104,
      "ACTION_CBIT_PLACEHOLDER_UNRESOLVED", Level.WARNING, BitTier.CBIT, "action", "",
      "Use a placeholder available at this dispatch stage or correct its spelling.");
  public static final BitCode UNKNOWN_ACTION = RtpBitCodes.ACTION.register(2105,
      "ACTION_CBIT_UNKNOWN_ACTION", Level.WARNING, BitTier.CBIT, "action", "",
      "Use one of the registered ACTION names.");
  public static final BitCode SESSION_ORPHANED = RtpBitCodes.ACTION.register(2106,
      "ACTION_CBIT_SESSION_ORPHANED", Level.WARNING, BitTier.CBIT, "action", "",
      "Check action lifecycle and teardown behavior before the session reaper window expires.");

  private static final ConcurrentHashMap<String, CbitCounter> MISSING_OBJECTIVES = new ConcurrentHashMap<>();
  private static final AtomicInteger TRACKED_OBJECTIVES = new AtomicInteger();
  private static final CbitCounter OBJECTIVE_OVERFLOW_COUNTER = new CbitCounter();
  private static final ConcurrentHashMap<GateKey, GateOutcomeCounter> GATE_OUTCOMES = new ConcurrentHashMap<>();
  private static final AtomicInteger TRACKED_GATES = new AtomicInteger();
  private static final CbitCounter UNRESOLVED_PLACEHOLDERS = new CbitCounter();
  private static final CbitCounter UNKNOWN_ACTIONS = new CbitCounter();
  private static final CbitCounter ORPHANED_SESSIONS = new CbitCounter();

  private static volatile int minimumGateSamples = DEFAULT_GATE_OUTCOME_SAMPLES;
  private static volatile String lastUnresolvedToken = "";
  private static volatile String lastUnknownAction = "";
  private static volatile String lastOrphanedAction = "";

  private ActionCbitCounters() {}

  /** Records a runtime read for an external objective whose score was absent. */
  public static void recordMissingObjective(String objective) {
    String key = objective == null || objective.isBlank() ? "<unknown>" : objective.trim();
    CbitCounter counter = objectiveCounter(key);
    counter.increment();
  }

  /** Records one result for a gate configuration, bounded by distinct gate identity. */
  public static void recordGateOutcome(Map<String, Object> gateConfig, String actionId, boolean passed) {
    if (gateConfig == null || gateConfig.isEmpty()) return;
    GateKey key = new GateKey(actionId == null || actionId.isBlank() ? "<unknown>" : actionId, gateConfig);
    GateOutcomeCounter counter = gateCounter(key);
    if (counter != null) counter.recordOutcome(passed);
  }

  /** Counts unresolved placeholder occurrences in a fully substituted payload. */
  public static void recordUnresolvedPlaceholders(String renderedPayload) {
    if (renderedPayload == null || renderedPayload.isEmpty()) return;
    Matcher matcher = UNRESOLVED_TOKEN.matcher(renderedPayload);
    while (matcher.find()) {
      UNRESOLVED_PLACEHOLDERS.increment();
      lastUnresolvedToken = matcher.group();
    }
  }

  /** Records an unknown ACTION name at dispatch while leaving the existing warning in place. */
  public static void recordUnknownAction(String actionName) {
    UNKNOWN_ACTIONS.increment();
    lastUnknownAction = actionName == null ? "<unknown>" : actionName.trim();
  }

  /** Records a session that remains active past the MemoryTracker reaper budget. */
  public static void recordOrphanedSession(String actionId) {
    ORPHANED_SESSIONS.increment();
    lastOrphanedAction = actionId == null ? "<unknown>" : actionId;
  }

  public static int minimumGateSamples() {
    return minimumGateSamples;
  }

  public static void setMinimumGateSamples(int samples) {
    if (samples < 1) throw new IllegalArgumentException("samples must be positive");
    minimumGateSamples = samples;
  }

  /** Captures a weakly-consistent immutable snapshot suitable for metrics consumers. */
  public static Snapshot snapshot() {
    Map<String, Long> objectives = new TreeMap<>();
    MISSING_OBJECTIVES.forEach((key, counter) -> objectives.put(key, counter.count()));
    long overflow = OBJECTIVE_OVERFLOW_COUNTER.count();
    if (overflow > 0) objectives.put(OBJECTIVE_OVERFLOW, overflow);

    List<GateOutcome> gates = new ArrayList<>();
    GATE_OUTCOMES.forEach((key, counter) -> gates.add(new GateOutcome(
        key.actionId(), gateType(key.gateConfig()), counter.evaluated(), counter.passed(),
        counter.outcome(minimumGateSamples))));
    gates.sort(java.util.Comparator.comparing(GateOutcome::actionId).thenComparing(GateOutcome::gateType));

    return new Snapshot(objectives, gates, UNRESOLVED_PLACEHOLDERS.count(), UNKNOWN_ACTIONS.count(),
        ORPHANED_SESSIONS.count(), minimumGateSamples, lastUnresolvedToken, lastUnknownAction,
        lastOrphanedAction);
  }

  /** Builds the live CBIT report from the current counters. */
  public static BitReport report() {
    Snapshot snapshot = snapshot();
    List<BitFinding> findings = new ArrayList<>();
    for (Map.Entry<String, Long> entry : snapshot.missingObjectiveReads().entrySet()) {
      addFinding(findings, OBJECTIVE_MISSING, "actions.scoreboard.objectives." + entry.getKey(),
          entry.getValue() + " scoreboard reads returned no score.", "an existing scoreboard objective", "");
    }
    for (GateOutcome gate : snapshot.gateOutcomes()) {
      if (gate.outcome() == GateOutcomeCounter.Outcome.NEVER_PASSED) {
        addFinding(findings, GATE_NEVER_PASSED, gatePath(gate),
            "The gate failed all " + gate.evaluations() + " sampled evaluations.",
            "at least one passing evaluation", "");
      } else if (gate.outcome() == GateOutcomeCounter.Outcome.ALWAYS_PASSED) {
        addFinding(findings, GATE_ALWAYS_PASSED, gatePath(gate),
            "The gate passed all " + gate.evaluations() + " sampled evaluations.",
            "a gate whose pass condition matches the intended behavior", "");
      }
    }
    if (snapshot.unresolvedPlaceholders() > 0) {
      addFinding(findings, PLACEHOLDER_UNRESOLVED, "actions.dispatch.placeholders",
          snapshot.unresolvedPlaceholders() + " unresolved placeholder occurrence(s); last token "
              + snapshot.lastUnresolvedToken() + ".",
          "all placeholders substituted before dispatch", "");
    }
    if (snapshot.unknownActions() > 0) {
      addFinding(findings, UNKNOWN_ACTION, "actions.dispatch.action",
          snapshot.unknownActions() + " unknown ACTION dispatch(es); last name " + snapshot.lastUnknownAction() + ".",
          "a registered ACTION name", "");
    }
    if (snapshot.orphanedSessions() > 0) {
      addFinding(findings, SESSION_ORPHANED, "actions.sessions.orphaned",
          snapshot.orphanedSessions() + " session(s) remained active beyond the reaper window; last action "
              + snapshot.lastOrphanedAction() + ".",
          "expire, cancel or teardown before the reaper window", "");
    }
    return BitReport.of(findings);
  }

  /** Test isolation for this process-global counter set. */
  public static void reset() {
    MISSING_OBJECTIVES.clear();
    TRACKED_OBJECTIVES.set(0);
    OBJECTIVE_OVERFLOW_COUNTER.reset();
    GATE_OUTCOMES.clear();
    TRACKED_GATES.set(0);
    UNRESOLVED_PLACEHOLDERS.reset();
    UNKNOWN_ACTIONS.reset();
    ORPHANED_SESSIONS.reset();
    minimumGateSamples = DEFAULT_GATE_OUTCOME_SAMPLES;
    lastUnresolvedToken = "";
    lastUnknownAction = "";
    lastOrphanedAction = "";
  }

  private static CbitCounter objectiveCounter(String key) {
    CbitCounter current = MISSING_OBJECTIVES.get(key);
    if (current != null) return current;
    while (true) {
      int tracked = TRACKED_OBJECTIVES.get();
      if (tracked >= MAX_TRACKED_OBJECTIVES) return OBJECTIVE_OVERFLOW_COUNTER;
      if (!TRACKED_OBJECTIVES.compareAndSet(tracked, tracked + 1)) continue;
      CbitCounter created = new CbitCounter();
      CbitCounter existing = MISSING_OBJECTIVES.putIfAbsent(key, created);
      if (existing == null) return created;
      TRACKED_OBJECTIVES.decrementAndGet();
      return existing;
    }
  }

  private static GateOutcomeCounter gateCounter(GateKey key) {
    GateOutcomeCounter current = GATE_OUTCOMES.get(key);
    if (current != null) return current;
    while (true) {
      int tracked = TRACKED_GATES.get();
      if (tracked >= MAX_TRACKED_GATES) return null;
      if (!TRACKED_GATES.compareAndSet(tracked, tracked + 1)) continue;
      GateOutcomeCounter created = new GateOutcomeCounter();
      GateOutcomeCounter existing = GATE_OUTCOMES.putIfAbsent(key, created);
      if (existing == null) return created;
      TRACKED_GATES.decrementAndGet();
      return existing;
    }
  }

  private static String gateType(Map<String, Object> config) {
    if (config == null || config.isEmpty()) return "<unknown>";
    return String.valueOf(config.keySet().iterator().next());
  }

  private static String gatePath(GateOutcome gate) {
    return "actions." + gate.actionId() + ".gates." + gate.gateType();
  }

  private static void addFinding(List<BitFinding> findings, BitCode code, String path,
                                 String message, String expected, String suggestion) {
    BitFinding finding = BitFinding.of(code, code.defaultSeverity(), "actions", path,
        message, expected, suggestion);
    BitSeverityPolicy.effectiveFinding(finding).ifPresent(findings::add);
  }

  public record GateOutcome(String actionId, String gateType, long evaluations, long passed,
                            GateOutcomeCounter.Outcome outcome) {}

  public record Snapshot(Map<String, Long> missingObjectiveReads, List<GateOutcome> gateOutcomes,
                         long unresolvedPlaceholders, long unknownActions, long orphanedSessions,
                         int gateOutcomeSamples, String lastUnresolvedToken, String lastUnknownAction,
                         String lastOrphanedAction) {
    public static final Snapshot EMPTY = new Snapshot(Map.of(), List.of(), 0, 0, 0,
        DEFAULT_GATE_OUTCOME_SAMPLES, "", "", "");

    public Snapshot {
      missingObjectiveReads = Collections.unmodifiableMap(new TreeMap<>(missingObjectiveReads));
      gateOutcomes = List.copyOf(gateOutcomes);
      lastUnresolvedToken = lastUnresolvedToken == null ? "" : lastUnresolvedToken;
      lastUnknownAction = lastUnknownAction == null ? "" : lastUnknownAction;
      lastOrphanedAction = lastOrphanedAction == null ? "" : lastOrphanedAction;
    }
  }

  private static final class GateKey {
    private final String actionId;
    private final Map<String, Object> gateConfig;
    private final int hashCode;

    private GateKey(String actionId, Map<String, Object> gateConfig) {
      this.actionId = actionId;
      this.gateConfig = gateConfig;
      hashCode = 31 * actionId.hashCode() + System.identityHashCode(gateConfig);
    }

    private String actionId() {
      return actionId;
    }

    private Map<String, Object> gateConfig() {
      return gateConfig;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof GateKey that
          && actionId.equals(that.actionId)
          && gateConfig == that.gateConfig;
    }
  }
}
