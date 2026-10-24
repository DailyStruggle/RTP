# BIT API

`bit-api` is a platform- and plugin-neutral contract for power-on, continuous, and initiated built-in tests. It has no RTP, platform, or YAML dependencies, and does not schedule work or write to a global logger. Callers provide execution and output context.

The JSON report format is versioned by the `schema` field in `BitReport`.
Finding codes are registered, not enumerated. An owner reserves a unique key prefix with `BitCodeRegistry.reserve(...)`, registers each `BitCode` next to the check that emits it, and builds findings with `BitFinding.of(code, ...)`. A code's stable identity is `(namespace, id, key)`: ids are local to their namespace, while keys are unique in the registry. The registry resolves ids with both namespace and id and rejects identity collisions at registration. `BitCodeNamespace.retire(...)` retains removed identities so they cannot be reused. The `BIT` namespace belongs to `bit-api` itself; RTP's release lock and allocation policy are documented in ADR-121 section 10.

Severities are JDK `java.lang.System.Logger.Level` values (`ERROR`, `WARNING`, `INFO`, `DEBUG`, `TRACE`); `ALL` and `OFF` are rejected. `BitLevels` maps a level to `BitHealth` and to `java.util.logging.Level` for hosts that log through JUL.

`BitRunner` registers `BitComponent`s, runs a named component or a selector, and retains each component's latest `BitResult`. Group selectors (`all`, `tier:<name>`, and `subsystem:<name>`) select only `SAFE` components; naming a component explicitly may run any side-effect class. The runner uses the caller-supplied `Executor` and creates no threads. A timeout bounds the wait after the executor accepts the check; a direct executor runs inline, so checks that may block need an asynchronous executor for timeout enforcement.

`BitOutputs.text(...)` and `BitOutputs.json(...)` render completed and not-yet-run component state to a caller-supplied `Consumer<String>`. They do not log, schedule, or depend on a chat API.

`BitTable.rows(component, result)` and `BitTable.render(rows, columns, decorator)` build an aligned CSV table: a header, then one row per finding (or one summary row, `NOT_RUN` when there is no result). Padding follows each comma and cells use RFC 4180 quoting, so the output parses as CSV when leading spaces are trimmed. Widths are measured before decoration, so a plugin's `Decorator` can add color codes without breaking alignment. `bit-api` adds no colors itself.
