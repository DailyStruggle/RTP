# ADR-125 - Whole-Server Discovery Fuzzing: Protocol Discovery, Agentless Observation, Attribution After Detection

**Status:** Accepted. Amends [ADR-111](ADR-111-live-server-behaviour-fuzzing.md) section 2.2 (*Plugin-agnostic* inputs, *Isolation*, *Same-run control* and *Stdlib Python*), and refines section 2.3 (*Attribution*) and section 2.7 (*Action vocabulary*), for a new whole-server mode only. Also amends [ADR-122](ADR-122-platform-neutral-stress-harness.md) decision 6 for server mode only (section 2.6). ADR-122 is itself Proposed: the carve-out takes effect only if both are accepted, and if ADR-122 is rejected the profile fields of section 2.6 are defined by this ADR alone. ADR-111 target mode is unchanged.
**Date:** 2026-10-23
**Relevant Requirements:** REQ-RTP-S-002, REQ-RTP-S-004
**Related ADRs:** [ADR-111](ADR-111-live-server-behaviour-fuzzing.md), [ADR-122](ADR-122-platform-neutral-stress-harness.md), [ADR-091](ADR-091-headless-player-protocol-driver-for-devstack.md), [ADR-080](ADR-080-opt-in-simulation-benchmark-tier.md), [ADR-094](ADR-094-quality-engineering-gates.md)

---

## 1. Context

[ADR-111](ADR-111-live-server-behaviour-fuzzing.md) Tier L fuzzes **one named target** at a time. Its per-target inputs are the command each bot sends (`--cmd`), optional package prefixes (`--plugin-packages`) and the target's plugin and jar names (`--names`, `--jars`). Each target runs on a server restarted with only its own jar plus the harness (section 2.2 *Isolation*). That design found a real defect: BetterRTP 3.6.13 retains the sessions of players who quit while a teleport is pending ([`LEAK_FUZZING.md`](../../helpers/StressTestRTP/LEAK_FUZZING.md), run `fuzz-20261010-213747`). It found it because the operator already knew which plugin to isolate and which command (`/betterrtp:rtp`) to send. The fit that flagged it scored a player census that named player classes. That census has since been removed in favour of the per-departure class screen, and whether the class screen alone flags this defect has not been measured.

A server admin with forty plugins does not know which plugin to suspect or which command to send. Three things in the current tool assume that knowledge:

- **Vocabulary is fixed.** `ACTIONS` and `DEFAULT_CMDS` in `scripts/bench_fuzz.py` hold a short list of verbs and one namespaced command per known plugin. Section 2.7 planned discovery from `plugin.yml` and tab completion, which is Bukkit-only and does not reflect what a given permission level can actually run.
- **Observation needs the harness.** Class histograms and plugin ownership come from StressTestRTP (`LeakProbe`), a Bukkit plugin. [ADR-122](ADR-122-platform-neutral-stress-harness.md) plans Fabric and NeoForge carriers, but they do not exist yet.
- **Attribution needs package prefixes.** `ServerLogOracle` attributes frames through `PLUGIN_PACKAGES` plus `--plugin-packages`, and stalls are attributed to `Server`.

Three properties of a running server make a target-free mode possible:

- The server sends every client a `declare_commands` packet at login: the Brigadier command graph, already filtered by that client's permissions. The packet has the same shape on Paper, Fabric and NeoForge.
- Any HotSpot JVM can be observed from the same host with `jcmd <pid> GC.class_histogram` (live objects, which forces a full GC) and `jcmd <pid> Thread.print`, with no agent installed.
- The jar set is a list of files. Every class a finding names can be mapped back to the jar that contains it, and a reproduced finding can be bisected over that list by restarting the server.

---

## 2. Decision

### 2.1 Two modes

- **Target mode** (`bench_fuzz.py`, default): ADR-111 unchanged, including its inputs, isolation, `direct` control and verdict.
- **Server mode** (`bench_fuzz.py --mode server`): fuzzes the whole installed jar set. Its only inputs shall be the host, the RCON details, the server directory, a platform profile (2.6), the permission tiers (2.5), the denylist extension (`--deny`) and the search budget. It shall not take `--cmd`, `--names`, `--jars` or `--plugin-packages`.

### 2.2 Amendment to ADR-111 section 2.2 (server mode only)

- **Plugin-agnostic** becomes **target-free:** no per-target input at all. No oracle, generator rule, score term or built-in denylist entry shall name a plugin, mod or class. Operator-supplied `--deny` entries (2.5) are exempt: they are site policy, not detection logic, and may name plugin commands.
- **Isolation** is relaxed: exploration runs with every installed jar loaded at once. Isolation moves after detection, into the jar `ddmin` (2.4), which restarts the server with jar subsets.
- **Same-run control** becomes the **vanilla baseline:** run first in the same run, on the same server build with the jar folder emptied except for the profile's prerequisite jars (2.6). Its outputs shall never be reused across runs. A signature or class that the baseline also produced shall never be replayed, reported or blamed on a jar.
- **Same seeds** means the same seed list and generator, applied to the tree the baseline itself discovers. The vanilla tree is a subset of the full tree, so the baseline does not run the programs exploration runs. It is a population control (class-growth fits, tick-gap p99, log signatures), not a per-program control. The per-finding vanilla check is the prerequisites-only replay of 2.4.
- **Stdlib Python** is widened: in server mode Python may also read `jcmd` output (text, through `subprocess`) and jar archives through the stdlib `zipfile` module (class entries for the jar index; `plugin.yml`, `paper-plugin.yml` and `fabric.mod.json` for hard dependencies). It shall still import no third-party package.
- **Seeded** holds unchanged: every episode stays reproducible from its seed and trace.

### 2.3 Discovery from the protocol

- Each bot shall capture the `declare_commands` packet it receives at login and serialise the node graph (kind, name, parser and properties, children, redirect, executable flag, suggestion type) into a swarm event. A `discover` control op shall log in one bot per permission tier for this purpose.
- The driver shall merge the trees of all tiers into one `commands.json`, recording per node which tiers can see it and its minimum visible tier (`default` before `op`). Namespaced duplicates (`betterrtp:rtp` and `rtp`) shall fold into one node with aliases. Redirects shall resolve with cycle protection.
- Argument values shall be generated from the declared parser type and its bounds (numeric ranges, `bool`, word / quoted / greedy strings, entity and game-profile selectors resolved to the bot or another bot, block and vector positions near the bot) and from tab-completion replies, cached per node. Numeric parsers (`brigadier:integer`, `brigadier:long`, `brigadier:float`, `brigadier:double`) with wide or default bounds shall sample from a boundary and magnitude ladder (`min`, `-1`, `0`, `1`, `16`, `64`, `256`, `1024`, a clamped upper cap and `max`, alongside uniform draws within bounds) so threshold bugs are reached without spending the budget on extreme 32-bit values. `plugin.yml` and mod metadata shall not be used for discovery.
- Bots choose behaviour through a bandit over (command node, follow-up shape) pairs. When both `default` and `op` tiers are active, the bandit shall stratify its episode budget so `default`-visible (player-reachable) nodes receive the primary share and `op`-only nodes cannot starve the player surface; `--tiers default` runs player-reachable nodes only. Follow-up shapes include quitting at once, quitting during a pending delay, rejoining, repeating, acting while another bot runs the same node, and rapid slot clicks plus window close when a command opens an inventory window. Because `bench-swarm.js` disables Mineflayer's `inventory` plugin under lean mode, window exploration shall handle `open_window`, `window_click` and `close_window` packets directly at the protocol level (or enable inventory handling in fuzz mode). Rewards are novelty (a new reply shape or log signature, as ADR-111 section 2.4 keys) and harm (class growth per action, tick-gap excess, kicks).

### 2.4 Detection first, attribution after

- **Leak oracle:** a per-feature class screen fits each class's post-GC growth against per-episode feature counts of the form `node`, `node x follow-up` and departures. It shall be Bonferroni-corrected over classes and features, and compared against the vanilla baseline. No class name shall be hardcoded (as ADR-122 decision 4 requires of the harness).
- **Stall oracle:** bots report gaps between `update_time` packets (one per 20 ticks) and keep-alive delay. Two signals are scored against the vanilla baseline, excluding intervals that overlap a driver-requested histogram (a full GC), thread dump or restart:
  1. **Acute spike:** a single `update_time` gap more than `--stall-ms` (default 2000) above the baseline p99.
  2. **Sustained tick inflation:** episode-wide `update_time` interval inflation (mean or p95 excess above the baseline distribution during `repeat` and `concurrent` arms) that degrades TPS under spam without crossing the single-gap threshold on one call.
  The driver shall take one or more `jcmd <pid> Thread.print` dumps while a stall or elevated tick interval is in progress; the signature is the recurring main-thread top frame that is neither JDK nor server code. This replaces ADR-111's "stalls are attributed to `Server`" in server mode; a stall with no such frame stays `server`.
- **Log oracle:** `ServerLogOracle` keeps the ADR-111 parsing and signature rules; in server mode frames are attributed through the jar index instead of package prefixes.
- **Jar index:** classes and frames shall be mapped to jars by scanning the jar folder's archives. A class in no jar is labelled `server`. This is a first suspect, not the verdict.
- **Shrinking and numeric threshold bisection:** before jar attribution, a reproduced finding is shrunk over actions and bots (`ddmin` and `shrink_bots`). When the shrunk program contains a numeric argument on the ladder, the shrinker shall bisect between a passing bound and the failing value to record the minimal reproducing threshold.
- **Pre-`ddmin` probes:** `ddmin` (`scripts/bench_fuzz.py`) assumes the full set reproduces, never tests the empty set and always returns at least one item. Two probes shall therefore run first, in order, each a restart followed by `--finding-n` replays of the shrunk program:
  1. **Full set** (every installed jar). Fewer than `--finding-k` reproductions: the finding is reported as not reproducible after restart and is not attributed.
  2. **Prerequisites only** (the empty candidate subset). At least `--finding-k` reproductions: the finding is labelled `server` (vanilla or a prerequisite), no `ddmin` runs and no jar is blamed. A program that sends a node absent from the vanilla tree gets the server's unknown-command reply and counts as not reproducing.
- **Jar `ddmin`:** a finding that reproduces (`--finding-k` of `--finding-n` replays, ADR-111 section 2.4), has been shrunk and has passed both pre-`ddmin` probes shall be confirmed by delta debugging over the non-prerequisite jar set. Each probe restarts the server with a subset plus the prerequisites and replays the shrunk program `--finding-n` times; it passes on at least `--finding-k` reproductions. Subsets shall be closed under hard dependencies read from jar metadata (`depend`, `depends`), and a probe in which a jar of the subset did not load is unresolved and counts as not reproducing. The result is one jar or a minimal set that needs each member.
- **Verdict and resolution output:** ADR-111 section 2.5 holds, with two additions in `verdict.json` and `report.txt`:
  1. **Operator mitigation:** the canonical command node, all discovered aliases, the minimum reproducing permission tier (`default` vs `op`, ranking player-reachable findings first), and any bisected argument bound, so an operator can block, alias-remap or revoke the command in `commands.yml` or their permission plugin when removing the jar is not immediate.
  2. **Author reproducer:** the oracle, the blamed jar set returned by `ddmin` (or the jar index's unconfirmed first suspect on `INCONCLUSIVE`), the leaked class slope (`GC.class_histogram`) or culprit stack frame (`Thread.print` / log), the shrunk program, the reproduction count (`k` of `n`) and a one-line `--replay` command.

### 2.5 Two permission tiers and a denylist

- `--tiers default,op` (or `--tiers default` for player-surface hardening). Default-tier bots join with no extra permissions and inherit whatever the server's existing configuration grants a new player; unlike target mode, server mode shall **never** run `LuckPerms` or permission-plugin grants (`lp group default permission set ...`), preserving the server's real boundary between `default` and `op`. Op-tier bots are opped through RCON `op <name>` before they join and de-opped with `deop` at episode end and at driver exit, including on error. No permission plugin is required.
- A denylist entry is a pattern `root [subpath] [<argkind>]`, matched after alias folding (so `minecraft:stop` matches `stop`):
  - `root` is a root literal with an optional trailing `*` glob (`ban*` matches `ban` and `ban-ip`). A root-only entry denies the root and its whole subtree.
  - `subpath` is a sequence of child literals (`reload confirm` denies only that path; `reload` alone stays allowed).
  - `<argkind>` denies any path whose next argument node has that parser kind (`kill <entity>` denies `kill` with a selector; bare `kill`, which targets the sender, stays allowed).
- Built-in entries: `stop`, `restart`, `op`, `deop`, `ban*`, `pardon*`, `whitelist`, `save-off`, `kill <entity>`, `execute`, `reload confirm`, `kick`, `difficulty`, `gamerule`, and the state-changing `tick` subcommands `tick freeze`, `tick unfreeze`, `tick rate`, `tick sprint` and `tick step` (vanilla 1.20.3+), because they change the tick rate the stall oracle measures. `tick query` stays allowed.
- `--deny` adds operator entries in the same syntax; nothing shrinks the list. A denied node shall never be sent, by generation, mutation, shrinking or replay.
- Server mode shall run only against a disposable bench copy made by `scripts/bench_setup.py`. No mechanism restores the world, `ops.json` or plugin configs between runs: each run shall start from a fresh copy, and an automatic restore is not part of this decision. The denylist limits damage; the copy bounds it.

### 2.6 Agentless observation and the platform profile

- The driver runs on the server host. It reads histograms and thread dumps with `jcmd`; the JVM is found from `--pid-file` or from `jcmd -l` matched against the server jar, and for a container the command is wrapped as `docker exec <container> jcmd 1 ...`. A missing `jcmd`, wrong user or unmatched PID shall fail fast with a hint; it shall never be read as an empty histogram (ADR-122 decision 2, *not measured*).
- If `/rtpstress` answers over RCON at the start of the run, StressTestRTP checkpoints may replace the `jcmd` histogram. The harness is optional in server mode, and the observer is chosen once per run. When the harness is chosen, its jar shall be treated as a prerequisite: kept in the baseline, in both pre-`ddmin` probes and in every `ddmin` subset, and never a `ddmin` candidate, so every comparison uses the same observer (as ADR-111 section 2.2 kept "plus the harness").
- The per-platform input is a profile (`paper`, `fabric`) extending ADR-122 decision 6: jar folder, log exception patterns, prerequisite jars always kept (for example the Via plugins on Paper, Fabric API and the loader on Fabric) and the restart command. A NeoForge profile follows the same shape.
- **Carve-out from ADR-122 decision 6 (server mode only).** Decision 6 requires that the ADR-111 invariants, *isolated* included, hold under every profile, and that stack-trace attribution through `--plugin-packages` apply to every profile. In server mode *isolated* is replaced by the pre-`ddmin` probes and jar `ddmin` (2.4), and `--plugin-packages` attribution by the jar index. Target mode keeps both clauses under every profile.

### 2.7 Gates

- ADR-111 section 2.8 holds: server mode is opt-in and local, never part of `gradlew test` or CI, and every oracle needs a planted-bug test against the fake server in `scripts/tests/` before it can affect the verdict.
- The method shall be validated against a planted-bug fixture with neutral names (a Paper plugin and a Fabric mod: a session that leaks when the player quits before a short timer completes, and a radius-gated main-thread busy loop), and against the known BetterRTP 3.6.13 positive. No fixture, BetterRTP or class name shall appear in any input, oracle, rule or built-in denylist entry, and evidence runs shall pass no `--deny` entry that names them.
- A published claim about another plugin's finding shall follow [ADR-080](ADR-080-opt-in-simulation-benchmark-tier.md).

---

## 3. Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Discover commands from `plugin.yml` / `fabric.mod.json` (ADR-111 section 2.7) | Platform-specific, misses Brigadier-registered commands and argument types, and does not reflect what a permission level can run |
| Keep isolation: run target mode once per installed jar | Needs a command per jar the operator does not have, and cannot see interactions between jars; one restart per jar costs more than one exploration plus a `ddmin` |
| Require the ADR-122 harness on every platform | Blocks Fabric and NeoForge on carriers that do not exist yet; `jcmd` already gives histograms and thread dumps |
| Attach a JVMTI or Java agent for allocation attribution | Changes launch flags and runtime behaviour; heavier than needed when a class-to-jar index plus `ddmin` confirms blame |
| Attribute by package prefixes | Needs a per-plugin list, which is the knowledge server mode does not have; shaded libraries share prefixes across jars |
| Permission tiers through LuckPerms groups | Bukkit-only and an extra dependency; RCON `op` works on every platform |
| Allow-list of safe commands instead of a denylist | Would need per-plugin knowledge and would hide the very commands under test |

---

## 4. Consequences

### Positive
- One command fuzzes an unknown Paper or Fabric server and reports "jar X, class Y per `node x follow-up`" or "jar X stalls the main thread after node Z" without being told what is installed.
- Blame is evidenced twice: by the jar index and by restarting without the jar. A finding that reproduces on the prerequisites alone is never blamed on a jar.
- Discovery reflects the permissions each tier really has, preserves the server's configured `default` group, and stratifies the bandit so `default`-tier (player-reachable) commands are not starved by a large `op` command tree.
- Findings emit both immediate operator mitigation (command node, aliases, minimum reproducing tier and bisected argument threshold) and an author-ready reproducer (`ddmin` jar set, shrunk program, class slope or stack frame, and `--replay` command).

### Negative / Trade-offs
- Many plugins add noise. The Bonferroni correction lowers sensitivity, and an intermittent leak such as BetterRTP's (about 24 or more episodes in target mode) may need a larger budget when it shares the bandit with every other node.
- Attribution costs restarts: two pre-`ddmin` probes, then for one culprit on the order of 2 x log2(n) subset probes, each a restart plus up to `--finding-n` replays. Interactions that need two jars cost more.
- Dropping the state-changing `tick` subcommands means a plugin bug reachable only under a frozen or sprinting tick loop is not explored.
- Op-tier fuzzing can damage the world or configs. It is bounded by the denylist and the disposable bench copy, not eliminated.
- `jcmd` needs a JDK and the server's user on the host, or `docker exec` into the container.
- The vanilla baseline doubles as the control, so baseline and exploration must use the same seeds and server build.

---

## 5. References

- `scripts/bench_fuzz.py` (`gen_program`, `mutate`, `Corpus.consider`, `ddmin`, `shrink_bots`, `screen_classes`, `screen_departures`, `ServerLogOracle`), `scripts/bench_overnight.py` (`Driver.set_jars`), `scripts/bench_setup.py`, `devstack/clients/bench-swarm.js` (`runFuzzProgram`)
- [`docs/dev/WHOLE_SERVER_FUZZING.md`](../dev/WHOLE_SERVER_FUZZING.md) (method, diagrams, validation targets)
- [`docs/dev/BEHAVIOUR_FUZZING_PLAN.md`](../dev/BEHAVIOUR_FUZZING_PLAN.md) (action vocabulary stage)
- [`helpers/StressTestRTP/LEAK_FUZZING.md`](../../helpers/StressTestRTP/LEAK_FUZZING.md) (BetterRTP 3.6.13 finding, run `fuzz-20261010-213747`)
- Zeller and Hildebrandt, *Simplifying and Isolating Failure-Inducing Input*, IEEE TSE 28(2), 2002 (`ddmin`)
- Auer, Cesa-Bianchi and Fischer, *Finite-time Analysis of the Multiarmed Bandit Problem*, Machine Learning 47, 2002 (UCB1)
