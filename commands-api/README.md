# Commands API

Platform-agnostic command framework used by RTP. Commands form a **tree of
nodes**; arguments split into **subcommands** (bare tokens) and **parameters**
(`key:value`); execution is deferred onto a tick-budgeted **pipeline**.

Read this before touching the module — three concerns (syntax, permissions,
dispatch) share one `onCommand` name, which is the #1 source of confusion.

---

## 1. Module layout

```
commands-api/src/main/java/io/github/dailystruggle/commandsapi/
├── common/                      platform-agnostic core
│   ├── CommandsAPI              global state, pipeline, execute() budget
│   ├── CommandsAPICommand       command contract
│   ├── CommandParameter         parameter contract
│   ├── CommandExecutor          pipeline work item (Runnable + future)
│   ├── Factory                  name→supplier registry
│   └── localCommands/TreeCommand  default traversal / tab-complete / help
└── bukkit/                      Bukkit bindings (optional)
    ├── BukkitCommand
    ├── BukkitParameter          bridges UUID callerId → CommandSender
    ├── localCommands/BukkitTreeCommand
    └── LocalParameters/         ready-made parameter types
```

Consumers depend on `common.*`. A platform adapter only supplies: sender
identity, message sink, permission resolver.

---

## 2. Core concepts

### 2.1 Tree
Each node exposes `name()`, `permission()`, `description()`, `parent()`, a
parameter map (`getParameterLookup()`), and a subcommand map
(`getCommandLookup()`). The root is the server-registered command (e.g.
`/rtp`). `TreeCommand.onCommand` / `onTabComplete` recurse with
`(args, i, tempParameters)`.

### 2.2 Argument syntax — the cardinal rule

| Form           | Meaning                     | Example          |
|----------------|-----------------------------|------------------|
| `name`         | subcommand (positional)     | `reload`         |
| `name=value`   | parameter (canonical)       | `player=steve`   |
| `name:value`   | parameter (legacy, accepted)| `player:steve`   |
| `name=v1,v2`   | parameter, multi-value      | `player=a,b`     |

Delimiters: `CommandsAPI.parameterDelimiter` (`=`, canonical — used in
tab-complete) and `CommandsAPI.parameterDelimiterAlt` (`:`, accepted on
input for backward compatibility). Multi-value delimiter:
`multiParameterDelimiter` (`,`).

> **A bare token (no `=` or `:`) is _always_ a subcommand name.** It is
> never treated as a "default" parameter value. Unknown bare token →
> `msgInvalidCommand`.

> **Tip:** if a parameter *value* contains `:` (e.g. a namespaced Minecraft
> ID like `iris:volcanic_ash_plains`), use `=` as the separator to avoid
> ambiguity: `biome=iris:volcanic_ash_plains`.

### 2.3 Parameters

`CommandParameter` (abstract) supplies:
- `Set<String> values()` — legal values (tab-complete source).
- `isRelevant: BiFunction<UUID,String,Boolean>` — per-caller filter →
  `relevantValues(UUID)`.
- `subParamMap` — additional parameters unlocked once a specific value is
  chosen (`subParams(value)`).
- `permission()` (gates visibility and use), `description()`, `priority`
  (tab-complete ordering).

Bundled Bukkit impls: see `bukkit/LocalParameters/Readme.md`.

### 2.4 Caller identity
The core uses `UUID callerId` — player UUID, or `CommandsAPI.serverId`
(`UUID(0,0)`) for console. Adapters translate back to platform senders.

### 2.5 Permissions
Threaded as a `Predicate<String> permissionCheckMethod`. **A subcommand or
parameter the caller lacks permission for is invisible, not forbidden**:
absent from tab-complete, and using its name produces `msgInvalidCommand` /
`msgBadParameter` rather than a permission error. This prevents
permission-probing.

---

## 3. Execution pipeline

### 3.1 Two `onCommand` overloads — don't confuse them

1. **Parser / dispatcher** (implemented by `TreeCommand`):
   ```java
   CompletableFuture<Boolean> onCommand(
       UUID callerId, Predicate<String> permissionCheckMethod,
       Consumer<String> messageMethod,
       String[] args, int i, Map<String,CommandParameter> tempParameters);
   ```
   Walks the tree, fires `msgInvalidCommand` / `msgBadParameter` on failure,
   enqueues a `CommandExecutor` on `CommandsAPI.commandPipeline`.

2. **Business logic** (override this when you write a command):
   ```java
   boolean onCommand(UUID callerId,
                     Map<String,List<String>> parameterValues,
                     CommandsAPICommand nextCommand);
   ```
   Receives already-parsed, already-validated parameters. Return `true` to
   continue into `nextCommand`.

Takes `String[] args` → parser. Takes `Map<String,List<String>>` → your code.

### 3.2 Pipeline

Parsed commands are deferred, not run inline. The host drains the queue per
tick:

```java
CommandsAPI.execute();            // up to ~50 ms (one tick)
CommandsAPI.execute(nanosBudget); // custom budget
```

`execute(budget)` runs at least one executor (so a starving queue still
progresses), then stops when `elapsed + next.avgTime() > budget`. Returns
remaining queue length. `avgTime()` is advisory but inaccurate values cause
tick overshoot.

Because execution is deferred and results are `CompletableFuture<Boolean>`,
tests must await the future — synchronous assumptions flake.

### 3.3 `CommandExecutor`
Holds `(command, callerId, parameterValues, nextCommand, messageMethod?,
result)`. Equality is `(command, callerId)` — lets "same caller spams same
command" be deduplicated if desired.

---

## 4. Error reporting

Failures go through callbacks, never a magic boolean return.

- **`msgInvalidCommand(callerId, arg[, msg])`** — bare token matching no
  visible subcommand (`/rtp frobnicate`). Platform adapters requiring
  auditing (RTP REQ-RTP-S-004) log at `WARNING`.
- **`msgBadParameter(callerId, name, value[, msg])`** — `key=value` (or
  `key:value`) where key is unknown, value is empty, or
  `isRelevant(callerId, value)` is false.

Consequences:
- Do **not** hand-parse positional args in business-logic `onCommand` — the
  parser already classified the bare token as a subcommand; a second parser
  causes double-dispatch.
- Do **not** ignore the parser's returned future — await or return it, so
  feedback (success or error callback) actually fires.

---

## 5. Tab completion

`onTabComplete` mirrors `onCommand`: same recursion, same permission filter.
Subcommand names and parameter keys suggest as bare words; parameter values
suggest as `key=value`, filtered by `relevantValues(callerId)`. `priority`
orders parameters that match the same prefix.

---

## 6. Writing a command

```java
public final class ReloadCmd extends SomeTreeCommand {
    public ReloadCmd(CommandsAPICommand parent) {
        super(parent, "reload", "rtp.reload", "Reload RTP configuration");
    }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String,List<String>> parameterValues,
                             CommandsAPICommand nextCommand) {
        RTP.getInstance().reload();
        return true; // continue into nextCommand
    }

    @Override public long avgTime() { return 5_000_000L; /* 5 ms */ }
}
```

Registering on a tree node:
```java
tree.addParameter("player", new OnlinePlayerParameter(
    "rtp.other", "target player",
    (sender, name) -> sender.hasPermission("rtp.other")
                   && Bukkit.getPlayerExact(name) != null));
tree.addSubCommand(new ReloadCmd(tree));
```

---

## 7. New parameter type

```java
public final class WorldParameter extends BukkitParameter {
    public WorldParameter(String perm, String desc,
                          BiFunction<CommandSender,String,Boolean> isRelevant) {
        super(perm, desc, isRelevant);
    }
    @Override public Set<String> values() {
        return Bukkit.getWorlds().stream().map(World::getName)
                     .collect(Collectors.toSet());
    }
}
```

Unlock further params once a value is chosen:
```java
w.subParamMap.put("world_nether", Map.of("portal", new BooleanParameter(...)));
```

---

## 8. Common pitfalls

- **Bare token ≠ default parameter.** `/rtp steve` is subcommand `steve`,
  not `player:steve`. Add shorthand at the adapter layer before handing
  `args` to `TreeCommand`.
- **Blocking on the parser's future on the main thread** — the pipeline
  runs on `execute()`; awaiting inline deadlocks the tick meant to run it.
- **Calling `msgInvalidCommand`/`msgBadParameter` from business logic** —
  the parser already fires them; doing so duplicates player messages and
  audit log entries.
- **Hand-building `parameterValues` in tests** — misses permission filter,
  `isRelevant` validation, and sub-param unlocking. Drive via the parser.
- **Treating permission-less elements as forbidden** — they're invisible
  and produce "invalid command". For a distinct denial, check permission
  explicitly in the leaf before delegating.
- **Bad `avgTime()`** — pipeline pacing relies on it. `0` on a 30 ms
  command overshoots ticks.

---

## 9. RTP integration

- `RTPCmd` delegates parsing to `TreeCommand.onCommand`; it must **not**
  run its own positional loop (double-dispatch — see `.junie/AGENTS.md`).
- Platform `BukkitBaseRTPCmd` overrides of `msgInvalidCommand` /
  `msgBadParameter` must `RTP.log(Level.WARNING, msg)` to satisfy
  REQ-RTP-S-004 auditing and surface in `rtp test full`.
- Brigadier integration belongs in `commands-api`, not per-platform
  adapters — see ADR-014 (`docs/adr/ADR-014-brigadier-bridge-via-commands-api.md`).

---

## 10. Cheat sheet

| Question                                    | Answer                                               |
|---------------------------------------------|------------------------------------------------------|
| Where does bare `foo` go?                   | Subcommand lookup only.                              |
| Where does `foo:bar` go?                    | Parameter lookup only.                               |
| Unknown `foo`?                              | `msgInvalidCommand(callerId, "foo")`.                |
| Unknown / empty / rejected `foo:bar`?       | `msgBadParameter(callerId, "foo", "bar")`.           |
| Who enforces permissions?                   | Caller-supplied `permissionCheckMethod`.             |
| Who runs my `onCommand`?                    | `CommandExecutor` via `CommandsAPI.execute(budget)`. |
| When?                                       | Next tick that drains the pipeline.                  |
| Testing?                                    | Await the parser's `CompletableFuture<Boolean>`.     |
