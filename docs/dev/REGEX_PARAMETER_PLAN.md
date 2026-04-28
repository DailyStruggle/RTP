# Plan: Regex Support in Command Parameters

## 1. Objective
Enable server administrators and players (with appropriate permissions) to use Java Regular Expressions in command parameters to target multiple entities or values dynamically.

Example: `/rtp player:reg:Admin.* world:reg:world_.*`

## 2. Proposed Syntax
- **Prefix:** `reg:`
- **Format:** `parameter:reg:<pattern>`
- **Combinability:** Can be mixed with literal values using the multi-parameter delimiter (comma): `player:Steve,reg:Admin.*`
- **Delimiter caveat:** patterns must not contain the multi-parameter delimiter (`,` — `CommandsAPI.multiParameterDelimiter`); a literal comma inside a pattern would split the value before regex expansion runs. A pattern may contain the parameter delimiter (`:` — `CommandsAPI.parameterDelimiter`) provided the `key:value` split honours a limit of `2` (see §3.1).

## 3. Implementation Strategy

### 3.1. Core Logic (`commands-api`)
The expansion logic resides in `TreeCommand.onCommand`
(`commands-api/src/main/java/io/github/dailystruggle/commandsapi/common/localCommands/TreeCommand.java`)
so every subcommand inherits the behavior through the default method.

#### Changes in `TreeCommand.onCommand`:
1. **Split limit for `key:value`:** replace
   `arg.split(String.valueOf(CommandsAPI.parameterDelimiter))` with
   `arg.split(String.valueOf(CommandsAPI.parameterDelimiter), 2)` so that a
   `reg:` prefix in the value half is preserved verbatim. Apply the same change
   to the sub-parameter loop further down (`argSplit2`) so nested parameters
   behave identically.
2. **Value expansion (top-level loop):** the current pipeline is
   `Arrays.stream(val.split(multiParameterDelimiter)).filter(isRelevant).collect(...)`.
   Refactor it to a `flatMap` step that, for every comma-separated token:
   - If the token starts with `reg:`, strip the prefix and compile the
     remainder via `java.util.regex.Pattern`. Stream
     `currentParameter.relevantValues(callerId)` and keep entries whose
     `Matcher.matches()` returns `true`.
   - Otherwise, emit the token as a singleton stream (literal path).
   - On `PatternSyntaxException`, fall back to treating the token as a literal
     string so a malformed pattern does not abort the command.
   The existing `isRelevant` filter must remain after the `flatMap` so literal
   tokens still validate; `relevantValues` already applies `isRelevant`, so
   regex-expanded tokens pass through unchanged.
3. **Sub-parameter loop:** apply the same `flatMap` refactor to the
   `vals2` pipeline so sub-parameters support regex symmetrically.
4. **Tab completion:** mirror the syntax in
   `TreeCommand.onTabComplete` only insofar as suggesting the `reg:` prefix; do
   not attempt to expand patterns during tab completion.

### 3.2. Integration and Safety
- **Permissions:** the `permission()` check on the parameter still gates the
  whole argument. Per-value access is enforced by the `isRelevant`
  `BiFunction<UUID,String,Boolean>` that `relevantValues(callerId)` already
  applies; no extra permission wiring is required, but parameter authors who
  embed permission checks inside `isRelevant` (e.g., `WorldParameter`) inherit
  them for free.
- **Efficiency:** compile patterns lazily, only when a token starts with
  `reg:`. Cache compiled `Pattern` instances per command invocation if the
  same token appears more than once.

## 4. Documentation Changes
- Update `docs/admin/COMMANDS.md` to include a section on "Regex Support".
- Provide examples for `player`, `world`, `region`, and `biome` parameters.
- Document the comma-in-pattern limitation noted in §2.

## 5. Verification Plan
- **Unit Tests:** create `RegexParameterTest.java` in `rtp-core` to verify:
    - Expansion of a single regex token against a stub `CommandParameter`.
    - Mixing literals and regex tokens in one value
      (`player:Steve,reg:Admin.*`).
    - Invalid regex falls back to a literal value (no exception escapes).
    - Regex expansion respects `isRelevant` (values that fail the predicate
      are dropped).
    - Sub-parameter regex expansion behaves identically to the top-level case.
- **Manual Testing:** verify in-game execution; tab completion is expected
  to suggest the `reg:` prefix only, not expand it.

## 6. Risks and Trade-offs
- **Performance:** matching against a very large set of values (e.g., every
  loaded biome) with a complex pattern adds work proportional to
  `relevantValues` size. Acceptable because commands are user-driven and not
  in tight loops.
- **Ambiguity:** a literal value that legitimately starts with `reg:` is
  reinterpreted as a pattern. World, player, region, and biome identifiers do
  not begin with `reg:` in practice, so this is an acceptable trade-off.
- **Delimiter collision:** a pattern containing the multi-parameter delimiter
  (`,`) is split before regex expansion sees it. Document the limitation
  rather than introduce an escape syntax.
