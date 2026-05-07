# CHECKLIST — Fabric/Brigadier flat tab-complete fallback (Bukkit parity)

**Effective Issue:** Tab-complete on Fabric is broken at the `/rtp ` boundary —
`/rtp <TAB>`, `/rtp s<TAB>`, etc. show nothing, but `/rtp scan` executes and
`/rtp scan <TAB>` (deeper) works. `/help rtp` (server-side dispatcher) lists
the children correctly, confirming the **server-side tree is built right** and
the gap is purely in client-side suggestion gathering for the children of the
root literal.

**Mode:** `[CODE]`

**Approved D-005 proposal (2026-05-06):** add a Bukkit-style flat
`SuggestionProvider` to the root literal of every adapted Brigadier tree, so
that root-level tab-completion ALWAYS surfaces every subcommand and every
parameter prefix in `name:` form, irrespective of whether per-child
`RequiredArgument.suggests(...)` providers are reachable from the client's
cached command tree.

**User-confirmed parameters:**
1. Suggestion suffix — emit parameter names with the trailing colon
   (`region:`, `biome:`, `world:` ...) so the player keeps typing the value
   directly. Mirrors the Bukkit `TabCompleter` wire format.
2. Always-on — fallback is permanently attached, not gated on
   "no child literal matched the prefix yet". Mirrors Bukkit (which always
   ran the `TabCompleter` for the current arg position).

---

## Implementation plan

- [x] 1. Add this checklist file to repo (this commit). — *evidence: this file*
- [x] 2. `BrigadierCommandAdapter.toBrigadier(root, ctx)` — after the existing
       `attachChildren(literal, root, root, ctx, ...)` call, attach a "shadow"
       `RequiredArgument("_", StringArgumentType.greedyString())` to `literal`
       with:
        - `.requires(...)` permissive (`s -> true`) so suggestion gathering
          never strips it.
        - `.suggests(flatSuggestionsFor(root, ctx))` — emits subcommand
          literal names + parameter names with `:` suffix, filtered by the
          builder's remaining prefix (case-insensitive).
        - `.executes(execute(root, ctx, List.of(...)))` so a player typing
          `/rtp foobar` reaches the root's `onCommand` (which routes to
          `msgInvalidCommand` per REQ-RTP-S-007).
       — *evidence: diff in `commands-api/src/main/java/.../BrigadierCommandAdapter.java`*
- [x] 3. Helper `flatSuggestionsFor(root, ctx)` — walk
       `tree.getCommandLookup()` and `tree.getParameterLookup()` (only when
       `root instanceof TreeCommand`); emit `entry.getValue().name()` for
       subcommands and `entry.getKey() + ":"` for parameters; suppress
       null/empty names; case-insensitive prefix match against
       `builder.getRemaining()`.
       — *evidence: same file, new private static method*
- [x] 4. Wrap suggestion gathering in the existing try/catch idiom (see
       `suggestionsFrom(...)`) so a throw in `tree.getCommandLookup()` /
       `getParameterLookup()` produces an empty completion + WARNING log,
       not a propagated exception.
       — *evidence: same method body*
- [x] 5. Unit test in `commands-api/src/test/java/.../BrigadierCommandAdapterFlatSuggestTest.java`:
        - Build a minimal `TreeCommand` with two subcommands (`scan`, `info`)
          and two parameters (`region`, `biome`).
        - Convert via `BrigadierCommandAdapter.toBrigadier(...)`.
        - Register against a fresh `CommandDispatcher`.
        - Call `dispatcher.getCompletionSuggestions(parse, cursor)` for the
          input `"rtp "` (cursor after the space) and assert the suggestion
          list contains `scan`, `info`, `region:`, `biome:`.
        - Assert prefix filtering works: input `"rtp re"` returns
          `region:` only.
       — *evidence: new test class, `:commands-api:test` green*
- [x] 6. Sanity-run `:commands-api:test` (full module) â€” 17/17 PASSED — *evidence: PASSED count*
- [x] 7. Sanity-run `:rtp-fabric:rtp-fabric-common:test` — confirm the existing
       `RTPCmdFabricRoot` Brigadier-adapter tests still pass with the new
       sibling node attached. — *evidence: PASSED count*
- [x] 8. Update `CHANGELOG.md` under the unreleased heading
       (`[3.0.0-beta.2] — Unreleased`) — single bullet under
       *Fixed*: "Restore tab-completion for the root `/rtp` literal on
       Fabric: a flat Bukkit-style suggestion fallback now lists every
       subcommand and every `param:` prefix at the root boundary,
       independent of per-child suggestion paths." Diff against the last
       released tag, not the working tree. — *evidence: changelog diff*
- [~] 9. (Deferred â€” see Notes) Add a row to the *Subproject ADRs* table referencing
       `commands-api-ADR-001` addendum **2026-05-06b** "flat root-suggestion
       fallback for Bukkit parity"; or, if the addendum scope already covers
       suggestion isolation, append a sub-bullet rather than a new addendum.
       — *evidence: ADR file diff*
- [x] 10. Submit. Final summary references this checklist with all boxes
        ticked or explicitly deferred.
        — *evidence: `submit` call*

---

## Risks / trade-offs (for the audit trail)

- The shadow `RequiredArgument("_")` node makes `/rtp <anything-else>` parse
  successfully and reach `execute(root, ...)` instead of erroring at the
  Brigadier layer. The root's `onCommand` already routes unknown tokens to
  `msgInvalidCommand` (REQ-RTP-S-007), so the user-visible behaviour is
  *better* (configurable message) rather than a regression. Documented in
  the proposal §Trade-offs.
- The fallback is "always-on" per user direction (2). When the prefix
  matches a real child literal (e.g., `/rtp sc`), Brigadier will now show
  both `scan` (from the literal child) and any `:`-suffixed parameter name
  starting with `sc` — typically nothing — so the duplication is benign in
  practice.
- This change is local to `commands-api`. It also benefits the eventual
  Velocity bridge for free — no per-platform code path needed.

## Out of scope (explicitly deferred)

- Forcing a client command-tree resync after lazy `CommandRegistrationCallback`
  invocations. `/help rtp` confirmed the server tree is correct, so the
  visible failure is at least partly client-tree-snapshot timing. The flat
  fallback fixes the user-facing UX deterministically; a separate audit of
  *when* `CommandRegistrationCallback` fires relative to `PlayerList.respawn`
  / op-grant is recorded for later under
  `docs/dev/POTENTIAL_BUGS.md` (no entry yet — to be added by step 1.5 if
  the symptom recurs after this fix lands).
- Permission gating (`rtp.scan`, `rtp.region`, ...) of suggestion entries.
  The flat provider currently emits *all* subcommands/parameters regardless
  of permission. This matches the Bukkit `TabCompleter` historic behaviour
  (it also emitted unfiltered names; the validator on dispatch enforced
  perms). Step F (`fabric-permissions-api` integration) will add filtering
  uniformly across both the per-child `requires(...)` and this flat
  provider.
