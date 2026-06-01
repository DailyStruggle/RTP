# commands-api

Platform-agnostic command framework used by RTP. One command tree is authored against `commands-api` and dispatched on every platform: Bukkit / Paper / Folia via the Bukkit command dispatcher, Fabric (and planned Velocity) via the Brigadier bridge.

This README is the **author-facing primer**. For the Brigadier bridge contract, recursion rules, suggestion-vs-validation split, and silent-failure isolation, see [`adr/commands-api-ADR-001-brigadier-bridge.md`](adr/commands-api-ADR-001-brigadier-bridge.md). For project-wide rules (S-00x, threading, architecture boundaries), see [`../../.junie/AGENTS.md`](../../.junie/AGENTS.md).

---

## TL;DR

1. A command is a `CommandsAPICommand`. Composite verbs implement `TreeCommand`.
2. **Sub-verbs** (e.g. `apply`, `confirm`, `rollback`) are registered with `addSubCommand(...)`.
3. **Named arguments** (e.g. `id=<...>`, `world=<...>`) are typed `CommandParameter`s registered in the constructor with `addParameter(name, parameter)`. They live as `name=value` on the wire.
4. The dispatcher accepts only two token shapes: bare literals routed as sub-commands, and `name=value` tokens routed as parameters. **There is no third "free positional" shape.** A bare token that does not match a registered sub-command triggers `msgInvalidCommand` and the verb returns `false`.
5. Implement `onCommand(UUID, Map<String,List<String>>, CommandsAPICommand nextCommand)` to do the work; read your inputs from `parameterValues.get("<name>")`.
6. **Do not override the `args[]`-form `onCommand`** to hand-roll positional parsing. The parser already turned the wire into a parsed map; overriding it re-implements `TreeCommand` poorly and breaks tab-completion.
7. **Tab-completion is free**: `CommandParameter.values()` is the enumerator. Override it to return a live set (e.g. world names, registered ids) and Brigadier and Bukkit both pick it up.

The prefab-verb regression that motivated this primer ([commit history of `PrefabApplyCmd`](../src/main/java/io/github/dailystruggle/rtp/common/commands/prefab/PrefabApplyCmd.java)) violated rules 4 and 6: the verb took the prefab id as a bare positional and tried to recover it with a custom args-form override. The fix was to register a typed `PrefabIdParameter` and move the wire form to `apply id=<id>`. Every other RTP verb (`InfoCmd`, `ConfigCmd`, `ReloadCmd`, region/world parameters) already used the canonical pattern. Use it.

---

## Core types

### `CommandsAPICommand`

Source: [`src/main/java/io/github/dailystruggle/commandsapi/common/CommandsAPICommand.java`](../src/main/java/io/github/dailystruggle/commandsapi/common/CommandsAPICommand.java).

The base interface. Two flavors of `onCommand`:

```java
// Dispatcher-facing entry point. Walks args[] and dispatches sub-commands
// and parameters. You normally do NOT override this on a leaf verb.
CompletableFuture<Boolean> onCommand(UUID callerId,
                                     Predicate<String> permissionCheckMethod,
                                     Consumer<String> messageMethod,
                                     String[] args,
                                     int i,
                                     Map<String, CommandParameter> tempParameters);

// Author-facing entry point. Called by the dispatcher with the parsed
// parameter map after args[] has been walked. This is where your logic
// goes.
boolean onCommand(UUID callerId,
                  Map<String, List<String>> parameterValues,
                  CommandsAPICommand nextCommand);
```

`nextCommand` is non-null exactly when the dispatcher matched a sub-command after some of the current verb's parameters. The contract is:

- If `nextCommand != null`, return `true` to let the chain continue to the child. Returning `false` short-circuits the chain and the child never runs. **Do not call `nextCommand.onCommand(...)` yourself**  -  the dispatcher already enqueued it via `CommandExecutor` and will invoke it when the parent's future completes.
- If `nextCommand == null`, this verb is the leaf. Read `parameterValues`, do the work, return success.

Auxiliary required surface:

- `name()`, `permission()`, `description()`, `parent()`  -  identity / auth / help text.
- `msgBadParameter(...)`, `msgInvalidCommand(...)`  -  must be configurable via `messages.yml` (S-007).
- `onTabComplete(...)`  -  `TreeCommand` provides a default that walks parameters / sub-commands and asks `CommandParameter.relevantValues(callerId)` for the suggestion set.

### `TreeCommand`

Source: [`src/main/java/io/github/dailystruggle/commandsapi/common/localCommands/TreeCommand.java`](../src/main/java/io/github/dailystruggle/commandsapi/common/localCommands/TreeCommand.java).

`TreeCommand extends CommandsAPICommand` is the composite. It supplies:

- `addParameter(String name, CommandParameter parameter)`  -  register a named argument.
- `addSubCommand(CommandsAPICommand command)`  -  register a sub-verb.
- `getParameterLookup()` / `getCommandLookup()`  -  the registries (lowercased / uppercased keys respectively).
- `onTabComplete(...)` and the `args[]`-form `onCommand(...)`  -  both walk the wire format the same way the dispatcher does at runtime.

**Wire-format grammar** (single delimiter `=`, set in `splitOnParamDelimiter`):

```
token        := SUBCOMMAND | PARAMETER
SUBCOMMAND   := <bare-literal>            ; routed via getCommandLookup().get(literal.toUpperCase())
PARAMETER    := <name>=<value>[,<value>]* ; routed via getParameterLookup().get(name.toLowerCase())
```

Values support:

- Comma-separated lists: `region=R1,R2`.
- `reg:<pattern>` to expand against the parameter's value set (filtered by `isRelevant`, **not** `isSuggestionRelevant`  -  security invariant pinned by `RegexParameterSecurityTest`).

When a `name=` token has an empty value (`name=`), the dispatcher calls `msgBadParameter` and aborts.

### `CommandParameter`

Source: [`src/main/java/io/github/dailystruggle/commandsapi/common/CommandParameter.java`](../src/main/java/io/github/dailystruggle/commandsapi/common/CommandParameter.java).

Abstract. One subclass per argument shape. Required surface:

```java
public abstract Set<String> values();           // enumerator (drives tab-complete)
public BiFunction<UUID, String, Boolean> isRelevant; // execute-time validator
```

Subclasses fall into two patterns:

1. **Enumerable**  -  `values()` returns a live set, e.g. `WorldParameter` returns server worlds, `PrefabIdParameter` returns `PrefabRegistry.list()` ids. Tab-complete shows the set; execute-time `isRelevant` re-validates.
2. **Free-form**  -  `values()` returns `Collections.emptySet()` and `isRelevant` is permissive. Used for opaque inputs the user must type, e.g. `PrefabTokenParameter` for a server-minted confirmation token. Tab-complete offers nothing; the parser still accepts whatever was typed.

**Suggestion vs validation** (commands-api-ADR-001 addendum, 2026-05-06):

- `isRelevant(UUID, String)` is the **execute-time** authorization gate. It is always enforced.
- `isSuggestionRelevant(UUID, String)` is the **tab-completion** filter. It defaults to permissive (`true`) so platforms without a reliable permission backend (Fabric pre-`fabric-permissions-api`) still show suggestions.
- Override `isSuggestionRelevant` to delegate to `isRelevant` if you want permission-filtered suggestions on Bukkit.
- Never rely on suggestion filtering as a security boundary.

`CommandParameter.subParams(value)` allows nested parameters that only become valid after a parent parameter takes a specific value (used for region/world-specific overrides). The Brigadier bridge attaches these as further child nodes; see the ADR for the cycle guard.

---

## Authoring a new verb (cookbook)

You are adding `/rtp foo bar id=<id>` where `bar` is a leaf sub-verb of an existing `FooCommand` and `<id>` enumerates against some live registry.

### 1. Write the parameter

```java
public class FooIdParameter extends CommandParameter {
    public FooIdParameter(String permission,
                          String description,
                          BiFunction<UUID, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        return FooRegistry.list().stream().map(Foo::id).collect(Collectors.toSet());
    }
}
```

Mirror `WorldParameter` if in doubt. If the input is opaque (a token, a free-form string), mirror `PrefabTokenParameter` and return `Collections.emptySet()`.

### 2. Write the verb

```java
public class FooBarCmd extends BaseRTPCmdImpl {
    public FooBarCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
        addParameter("id",
                new FooIdParameter(FooCommand.PERMISSION,
                        "registered foo id",
                        (uuid, s) -> true));
    }

    @Override public String name()        { return "bar"; }
    @Override public String permission()  { return FooCommand.PERMISSION; }
    @Override public String description() { return "do the bar thing to a foo"; }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        // Chained dispatch: let the child run. Do not invoke it yourself.
        if (nextCommand != null) return true;

        if (callerId == null) {
            RTP.log(Level.WARNING, "/rtp foo bar rejected: no caller UUID");
            return false;
        }

        List<String> idValues = parameterValues == null ? null : parameterValues.get("id");
        String id = (idValues == null || idValues.isEmpty()) ? null : idValues.get(0);
        if (id == null || id.isEmpty()) {
            send(callerId, "&cUsage: &f/rtp foo bar id=<id>");
            return false;
        }

        // ... do the work ...
        return true;
    }
}
```

### 3. Wire it into the parent

In `FooCommand`'s constructor:

```java
addSubCommand(new FooBarCmd(this));
```

### 4. Add user-facing strings to `messages.yml`

`msgInvalidCommand` / `msgBadParameter` overrides for platform adapters must log via `RTP.log(Level.WARNING, msg)` per the AGENTS.md rule (REQ-RTP-S-004 auditing). User-facing strings ship under `rtp-plugin/src/main/resources/messages.yml` and propagate through the locale TSV pipeline (see AGENTS.md *Locale Config TSV Pipeline*).

### 5. Test it

- Targeted: a unit test that calls `verb.onCommand(callerId, params, null)` directly with a populated `parameterValues` map. See the `prefab/` tests for the pattern.
- Integration: a `TreeCommand`-driven test that hands an `args[]` to the parent and asserts the wire form parses through correctly.
- Tab-completion: assert `verb.onTabComplete(callerId, perms, new String[]{"id="}, 0, null)` returns the live id set.

---

## Anti-patterns (do not do these)

These are taken from real regressions in the RTP repo. Each has a "wrong" form and a one-line "right" form.

### Anti-pattern 1: hand-rolling positional parsing

**Wrong:**

```java
@Override
public CompletableFuture<Boolean> onCommand(UUID callerId,
                                            Predicate<String> permissionCheckMethod,
                                            Consumer<String> messageMethod,
                                            String[] args, int i,
                                            Map<String, CommandParameter> tempParameters) {
    // ... loop over args[i..] yourself, stash the first bare token as the id ...
}
```

**Why it breaks:** `TreeCommand`'s parser routes any bare token through sub-command lookup. An unknown bare token (e.g. a hyphenated id like `low-performance`) hits `msgInvalidCommand` and never reaches your map-form `onCommand`. Overriding the `args[]`-form to recover the positional re-implements parsing for one verb, diverges from every other verb in the tree, and breaks tab-completion (Brigadier walks `CommandParameter`s, not your custom override).

**Right:** Register the input as a typed `CommandParameter` with `addParameter("id", new FooIdParameter(...))` and use `name=value` on the wire (`apply id=<id>`).

### Anti-pattern 2: calling `nextCommand.onCommand(...)` from inside your `onCommand`

**Wrong:**

```java
if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);
```

**Why it breaks:** The dispatcher already enqueued the child via `CommandExecutor` (see `TreeCommand.java` lines ~246-272). Invoking it yourself runs it twice (or on the wrong thread, defeating the deliberate `whenComplete`-on-completing-thread choice that protects the Folia menu-redeem path).

**Right:** `if (nextCommand != null) return true;`  -  let the dispatcher's `cont.whenComplete` invoke the child.

### Anti-pattern 3: returning `false` from the pre-pass when the chain has a child

**Wrong:**

```java
public boolean onCommand(UUID callerId, Map<String,List<String>> parameterValues,
                         @Nullable CommandsAPICommand nextCommand) {
    if (parameterValues.isEmpty()) return false;  // eager fail
    // ...
}
```

**Why it breaks:** When `nextCommand != null`, this `onCommand` is the **pre-pass** for the chain  -  `parameterValues` is the parent's parsed args, and the child has not run yet. Returning `false` short-circuits the chain before the child can read its own args.

**Right:** Distinguish the two cases explicitly with the `if (nextCommand != null) return true;` guard above.

### Anti-pattern 4: hardcoded user-facing error strings

**Wrong:**

```java
messageMethod.accept("Unknown command, type /rtp help");
```

**Why it breaks:** S-007 requires "busy" and "invalid command" messages to be configurable. Hardcoded strings ship under every locale without translation and cannot be overridden by server admins.

**Right:** Route through `msgInvalidCommand(callerId, arg, messageMethod)`, which the platform adapter wires to the configured `messages.yml` key.

### Anti-pattern 5: silently failing inside a parameter's `values()` or `isRelevant`

**Wrong:**

```java
public Set<String> values() {
    return RTP.serverAccessor.getWorlds().stream()  // NPE during early init
            .map(World::getName)
            .collect(Collectors.toSet());
}
```

**Why it breaks:** Brigadier silently swallows exceptions from suggestion futures. A throwing `values()` deletes the suggestion list with no diagnostic, and on Bukkit it can abort the whole `toBrigadier` traversal (see ADR-001 addendum "Silent failure isolation"). The bridge now isolates per-attach failures, but you should still null-guard.

**Right:** Defensive null checks; return `Collections.emptySet()` if the backing registry is not yet bound; throw `IllegalStateException` (not a silent null) from public `rtp-api` entry points per S-006.

---

## Platform dispatchers

You should not need to touch these for a normal verb, but knowing they exist helps when debugging dispatch differences:

- **Bukkit / Paper / Folia:** `commands-api` registers via the `bukkit/` adapter package. The Bukkit dispatcher parses the wire format itself and calls `TreeCommand.onCommand(args)`. Source: [`src/main/java/io/github/dailystruggle/commandsapi/bukkit/`](../src/main/java/io/github/dailystruggle/commandsapi/bukkit/).
- **Fabric (and planned Velocity):** `BrigadierCommandAdapter` walks the same `TreeCommand` and emits a Brigadier node graph. The reconstructed wire is fed back into `TreeCommand.onCommand(args)` so behavior matches Bukkit byte-for-byte. Source: [`src/main/java/io/github/dailystruggle/commandsapi/brigadier/`](../src/main/java/io/github/dailystruggle/commandsapi/brigadier/). Contract: [`adr/commands-api-ADR-001-brigadier-bridge.md`](adr/commands-api-ADR-001-brigadier-bridge.md).

The Brigadier bridge attaches **nested** (`subParams`) and **sibling** parameters as child nodes so `/rtp region=R world=W shape=S` is reachable from any starting parameter. A path-local `paramsSeen` set bounds tree fanout; sub-commands are not attached after a parameter (matching the Bukkit "sub-commands are head literals" semantics).

---

## Testing conventions

- Targeted unit tests live alongside the verb (e.g. `rtp-core/src/test/.../prefab/PrefabApplyCmdTest.java`). They call `onCommand(callerId, map, null)` with a hand-built `parameterValues` map; this exercises the leaf logic without spinning up the dispatcher.
- Wire-format tests live under `commands-api/src/test/.../localCommands/` and `commands-api/src/test/.../brigadier/`. They hand an `args[]` to the parent and assert both parse-time routing (right child invoked, right `parameterValues` populated) and Brigadier tree shape (`BrigadierTreeShapeTest`).
- Security tests (`RegexParameterSecurityTest`, S-INJ-1 .. S-INJ-18) pin that `reg:<pattern>` expansion runs through `isRelevant`, not `isSuggestionRelevant`. Do not "fix" `expandRegexToken` to use the suggestion hook.

REQ-traceable tests should reference the REQ-* id in the class name (e.g. `ReqApiArch005BrigadierBridgeTest`) per AGENTS.md.

---

## Cross-references

- [`adr/commands-api-ADR-001-brigadier-bridge.md`](adr/commands-api-ADR-001-brigadier-bridge.md)  -  Brigadier bridge contract, recursion, suggestion-vs-validation split, silent-failure isolation.
- [`../../docs/dev/DESIGN.md#brigadier-bridge-commands-api`](../../docs/dev/DESIGN.md)  -  implementation notes for the bridge.
- [`../../docs/dev/REQUIREMENTS.md`](../../docs/dev/REQUIREMENTS.md)  -  S-007 (configurable command failure messages), REQ-RTP-F-013 (configurable user-facing strings), REQ-API-ARCH-005/006 (command framework contracts).
- [`../../docs/dev/TRACEABILITY.md`](../../docs/dev/TRACEABILITY.md)  -  REQ-* to test mapping.
- [`../../.junie/AGENTS.md`](../../.junie/AGENTS.md)  -  project-wide agent guide; the *Required Reading* table links back here for command-authoring tasks.

When in doubt: copy the shape of `InfoCmd`, `ConfigCmd`, or any of the `prefab/` verbs. They all use the canonical pattern documented above.
