package io.github.dailystruggle.commandsapi.brigadier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * commands-api-ADR-001 (Brigadier Bridge) — flat root-suggestion fallback
 * regression test (CHECKLIST-fabric-flat-tabcomplete, 2026-05-06b).
 *
 * <p>The Bukkit-parity flat-suggestion fallback attaches a permissive
 * {@code RequiredArgument("_", greedyString)} sibling under the root literal
 * with a {@link com.mojang.brigadier.suggestion.SuggestionProvider} that
 * emits every subcommand name and every parameter prefix in {@code name:}
 * form. This test pins the contract so a future refactor of
 * {@link BrigadierCommandAdapter#toBrigadier} that drops the fallback fails
 * here rather than silently regressing the Fabric tab-complete UX.
 *
 * <p>Verified properties:
 * <ul>
 *   <li>At {@code "rtp "} (cursor after the space), the suggestion list
 *       contains every subcommand literal name and every {@code param:}
 *       prefix.</li>
 *   <li>At {@code "rtp re"}, only {@code region:} is suggested
 *       (case-insensitive prefix match).</li>
 *   <li>At {@code "rtp sc"}, both the literal child {@code scan} (from the
 *       existing literal-child machinery) and the flat fallback are still
 *       reachable; we assert {@code scan} is in the result set.</li>
 * </ul>
 */
@DisplayName("commands-api-ADR-001 — Brigadier flat root-suggestion fallback (Bukkit parity)")
class BrigadierFlatRootSuggestionTest {

    private static final class Source {
        final UUID id = new UUID(7L, 9L);
    }

    private static BrigadierBridgeContext<Source> permissive() {
        return new BrigadierBridgeContext<>(
                src -> src.id,
                (src, perm) -> true,
                (src, msg) -> { /* discard */ });
    }

    private static StubTreeCommand fixtureRoot() {
        StubTreeCommand root = new StubTreeCommand("rtp");
        root.addParameter("region", new StringParam());
        root.addParameter("biome", new StringParam());
        root.addSubCommand(new StubTreeCommand("scan"));
        root.addSubCommand(new StubTreeCommand("info"));
        return root;
    }

    private static Set<String> suggestionsFor(String input) throws Exception {
        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(fixtureRoot(), permissive()));
        ParseResults<Source> parse = dispatcher.parse(input, new Source());
        Suggestions s = dispatcher.getCompletionSuggestions(parse).get();
        return s.getList().stream().map(Suggestion::getText).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("/rtp <empty> — flat list contains all subcommands and all `param:` prefixes")
    void emptyPrefixListsEverything() throws Exception {
        Set<String> out = suggestionsFor("rtp ");
        assertTrue(out.contains("scan"),  "expected literal 'scan' at root: " + out);
        assertTrue(out.contains("info"),  "expected literal 'info' at root: " + out);
        assertTrue(out.contains("region="), "expected 'region=' (canonical wire format): " + out);
        assertTrue(out.contains("biome="),  "expected 'biome=' (canonical wire format): " + out);
    }

    @Test
    @DisplayName("/rtp re — flat list narrows to 'region=' (case-insensitive prefix match)")
    void prefixFiltersToRegion() throws Exception {
        Set<String> out = suggestionsFor("rtp re");
        assertTrue(out.contains("region="),
                "expected 'region=' for prefix 're': " + out);
        assertFalse(out.contains("biome="),
                "did not expect 'biome=' for prefix 're': " + out);
        assertFalse(out.contains("scan"),
                "did not expect 'scan' for prefix 're': " + out);
    }

    @Test
    @DisplayName("/rtp sc — flat list still contains 'scan' alongside the existing literal child")
    void prefixFiltersToScan() throws Exception {
        Set<String> out = suggestionsFor("rtp sc");
        assertTrue(out.contains("scan"),
                "expected 'scan' (from literal child and/or flat fallback) for prefix 'sc': " + out);
        assertFalse(out.contains("region="),
                "did not expect 'region=' for prefix 'sc': " + out);
    }

    // ------------------------------------------------------------------
    // Stubs (mirrors BrigadierTreeShapeTest fixtures, kept local so the
    // two test classes can evolve independently)
    // ------------------------------------------------------------------

    private static class StringParam extends CommandParameter {
        StringParam() {
            super("", "", (uuid, s) -> true);
        }

        @Override
        public Set<String> values() {
            Set<String> out = new HashSet<>();
            out.add("foo");
            out.add("bar");
            return out;
        }

        @Override
        public Map<String, CommandParameter> subParams(String parameter) {
            return subParamMap.get(parameter);
        }
    }

    private static class StubTreeCommand implements TreeCommand {
        private final String name;
        private final Map<String, CommandParameter> parameters = new ConcurrentHashMap<>();
        private final Map<String, CommandsAPICommand> subCommands = new ConcurrentHashMap<>();

        StubTreeCommand(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String permission() { return ""; }
        @Override public String description() { return ""; }
        @Override public CommandsAPICommand parent() { return null; }
        @Override public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) { }
        @Override public long avgTime() { return 0L; }
        @Override public Map<String, CommandParameter> getParameterLookup() { return parameters; }
        @Override public Map<String, CommandsAPICommand> getCommandLookup() { return subCommands; }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) {
            return true;
        }

        @Override
        public List<String> help(UUID callerId, Predicate<String> permissionCheckMethod) {
            return new ArrayList<>();
        }
    }
}
