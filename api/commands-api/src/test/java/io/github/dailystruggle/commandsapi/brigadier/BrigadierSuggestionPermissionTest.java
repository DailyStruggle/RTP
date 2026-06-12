package io.github.dailystruggle.commandsapi.brigadier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
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
 * commands-api-ADR-001 (Brigadier Bridge) — regression test for permission
 * filtering of the greedy {@code args} slot suggestions.
 *
 * <p>Fabric and NeoForge dispatch the {@code /rtp} command tree through
 * {@link BrigadierCommandAdapter}. The greedy {@code args} slot's
 * {@link com.mojang.brigadier.suggestion.SuggestionProvider} previously emitted
 * every subcommand name and {@code paramName=} key regardless of the caller's
 * permissions, so players were shown (and tab-completed) commands they could
 * not execute. This test pins the Bukkit-parity contract: Stage-1 suggestions
 * are filtered by {@link BrigadierBridgeContext#permissionCheck()}.
 *
 * <p>Two mechanisms gate suggestions in production:
 * <ul>
 *   <li>Sub-command literal nodes are gated by {@code requires(...)}; a real
 *       Minecraft server only sends the requirement-filtered command tree to
 *       the client, so a player without permission never sees the literal.
 *       We assert this via {@link CommandNode#canUse} (vanilla Brigadier's
 *       {@code getCompletionSuggestions} does not itself apply {@code canUse}
 *       to literal children, so the client-side packet filtering is the
 *       production guarantee).</li>
 *   <li>The greedy {@code args} slot's dynamic suggestion provider is evaluated
 *       server-side on every keystroke and previously leaked every
 *       {@code paramName=} key and subcommand name. The {@code paramName=}
 *       suggestions come ONLY from this provider, so asserting on them directly
 *       exercises the fix.</li>
 * </ul>
 */
@DisplayName("commands-api-ADR-001 — Brigadier suggestion permission filtering (Bukkit parity)")
class BrigadierSuggestionPermissionTest {

    private static final class Source {
        final UUID id = new UUID(7L, 9L);
    }

    /** Grants only the permissions in {@code granted}; empty perms are open. */
    private static BrigadierBridgeContext<Source> withPermissions(Set<String> granted) {
        return new BrigadierBridgeContext<>(
                src -> src.id,
                (src, perm) -> perm == null || perm.isEmpty() || granted.contains(perm),
                (src, msg) -> { /* discard */ });
    }

    private static StubTreeCommand fixtureRoot() {
        StubTreeCommand root = new StubTreeCommand("rtp", "");
        root.addParameter("region", new StringParam("rtp.params.region"));
        root.addParameter("biome", new StringParam("rtp.params.biome"));
        root.addSubCommand(new StubTreeCommand("scan", "rtp.scan"));
        root.addSubCommand(new StubTreeCommand("info", "rtp.info"));
        return root;
    }

    private static Set<String> suggestionsFor(String input, Set<String> granted) throws Exception {
        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(fixtureRoot(), withPermissions(granted)));
        ParseResults<Source> parse = dispatcher.parse(input, new Source());
        Suggestions s = dispatcher.getCompletionSuggestions(parse).get();
        return s.getList().stream().map(Suggestion::getText).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("greedy args slot: caller without permission gets no `paramName=` keys")
    void deniedCallerSeesNoParamKeys() throws Exception {
        Set<String> out = suggestionsFor("rtp ", Set.of());
        assertFalse(out.contains("region="), "region= must be hidden without permission: " + out);
        assertFalse(out.contains("biome="), "biome= must be hidden without permission: " + out);
    }

    @Test
    @DisplayName("greedy args slot: caller sees only the `paramName=` keys they hold")
    void grantedSubsetOfParamKeysIsFiltered() throws Exception {
        Set<String> out = suggestionsFor("rtp ", Set.of("rtp.params.region"));
        assertTrue(out.contains("region="), "region= expected with permission: " + out);
        assertFalse(out.contains("biome="), "biome= must be hidden without permission: " + out);
    }

    @Test
    @DisplayName("greedy args slot: fully-permissioned caller sees every `paramName=` key")
    void fullyPermissionedSeesAllParamKeys() throws Exception {
        Set<String> out = suggestionsFor("rtp ",
                Set.of("rtp.params.region", "rtp.params.biome"));
        assertTrue(out.contains("region="), out.toString());
        assertTrue(out.contains("biome="), out.toString());
    }

    @Test
    @DisplayName("sub-command literal nodes are gated by requires(...) per permission")
    void subCommandLiteralsAreGatedByRequires() {
        Source source = new Source();
        LiteralCommandNode<Source> rootNode = BrigadierCommandAdapter
                .toBrigadier(fixtureRoot(), withPermissions(Set.of("rtp.scan")))
                .build();
        CommandNode<Source> scan = rootNode.getChild("scan");
        CommandNode<Source> info = rootNode.getChild("info");
        assertNotNull(scan, "scan node present in tree");
        assertNotNull(info, "info node present in tree");
        assertTrue(scan.canUse(source), "scan usable with rtp.scan granted");
        assertFalse(info.canUse(source), "info gated: rtp.info not granted");
    }

    // ------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------

    private static class StringParam extends CommandParameter {
        StringParam(String permission) {
            super(permission, "", (uuid, s) -> true);
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
        private final String permission;
        private final Map<String, CommandParameter> parameters = new ConcurrentHashMap<>();
        private final Map<String, CommandsAPICommand> subCommands = new ConcurrentHashMap<>();

        StubTreeCommand(String name, String permission) {
            this.name = name;
            this.permission = permission;
        }

        @Override public String name() { return name; }
        @Override public String permission() { return permission; }
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
