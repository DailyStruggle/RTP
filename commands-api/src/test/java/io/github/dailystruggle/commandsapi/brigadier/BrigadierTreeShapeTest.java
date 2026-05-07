package io.github.dailystruggle.commandsapi.brigadier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * commands-api-ADR-001 (Brigadier Bridge) — recursion / sibling-chain / cycle-guard
 * regression test added 2026-05-06 by CHECKLIST-fabric-tabcompletion-audit P4.
 *
 * <p>Validates that {@link BrigadierCommandAdapter#toBrigadier} produces a Brigadier
 * tree that is reachable for the typical RTP-style usage:
 *
 * <ul>
 *   <li>{@code root sub p:value} — sub-command with a parameter parses cleanly.</li>
 *   <li>{@code root a:v b:v} — sibling parameters can be chained in any order
 *       (mirrors Bukkit's free-token wire format).</li>
 *   <li>{@code root a:v x:v} — nested ({@code subParams}) parameters under a parent
 *       parameter are reachable.</li>
 *   <li>{@code root a:v a:v} — repeating the same parameter name is rejected (cycle
 *       guard prevents factorial fanout).</li>
 *   <li>The total node count stays under a documented bound, so an accidental loss
 *       of the cycle guard surfaces as a test failure rather than a runtime DoS on
 *       Brigadier's join-time tree send.</li>
 * </ul>
 *
 * <p>Companion to {@link ReqApiArch005BrigadierBridgeTest} which already covers
 * single-parameter execution wire-format.
 */
@DisplayName("commands-api-ADR-001 — Brigadier tree shape (recursion + sibling-chain + cycle guard)")
class BrigadierTreeShapeTest {

    private static final class Source {
        final UUID id = new UUID(7L, 9L);
    }

    private static BrigadierBridgeContext<Source> permissive() {
        return new BrigadierBridgeContext<>(
                src -> src.id,
                (src, perm) -> true,
                (src, msg) -> { /* discard */ });
    }

    /** Build a fixture: root with two top-level params (a -> nested x,y; b) and a sub-command (sub) carrying p. */
    private static StubTreeCommand fixtureRoot() {
        StubTreeCommand root = new StubTreeCommand("rtp");

        // Top-level param 'a' with nested params {x, y} (CommandParameter.subParams contract).
        StringParam a = new StringParam();
        Map<String, CommandParameter> aNested = new HashMap<>();
        aNested.put("x", new StringParam());
        aNested.put("y", new StringParam());
        a.subParamMap.put("a", aNested);
        root.addParameter("a", a);

        // Sibling top-level param 'b'.
        root.addParameter("b", new StringParam());

        // Sub-command 'sub' carrying its own param 'p'.
        StubTreeCommand sub = new StubTreeCommand("sub");
        sub.addParameter("p", new StringParam());
        root.addSubCommand(sub);

        return root;
    }

    @Test
    @DisplayName("/root sub p:value — sub-command parameters reachable")
    void subCommandParameterReachable() throws CommandSyntaxException {
        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(fixtureRoot(), permissive()));
        // Non-throwing parse + execute is the success criterion; exit code is 1 because StubTreeCommand.onCommand returns true.
        // The user types only the value at the dispatcher; the adapter reconstructs
        // the commands-api 'name=value' wire format internally (see
        // BrigadierCommandAdapter.reconstructArgs and the parity test
        // ReqApiArch005BrigadierBridgeTest#parameterDispatchReconstructsWireFormat).
        dispatcher.execute("rtp sub hello", new Source());
    }

    @Test
    @DisplayName("/root a:v b:v — sibling parameter chain reachable")
    void siblingChainReachable() throws CommandSyntaxException {
        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(fixtureRoot(), permissive()));
        // The user types positional tokens; the bridge maps them to parameter slots in
        // the order Brigadier walks the tree. With sibling chaining the order is free.
        dispatcher.execute("rtp foo bar", new Source());
    }

    @Test
    @DisplayName("/root a:v x:v — nested subParams chain reachable")
    void nestedChainReachable() throws CommandSyntaxException {
        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(fixtureRoot(), permissive()));
        // 'a' followed by nested 'x' / 'y' (subParams). Two positional values.
        dispatcher.execute("rtp foo qux", new Source());
        dispatcher.execute("rtp foo quux", new Source());
    }

    @Test
    @DisplayName("/root a:v a:v — cycle guard blocks repeating the same parameter")
    void cycleGuardBlocksRepeat() {
        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(fixtureRoot(), permissive()));
        // The second 'a=' must not parse against the same param node — Brigadier
        // sees no graph edge for it, so dispatch raises CommandSyntaxException.
        // We can't trigger "the same parameter twice" via positional tokens
        // because Brigadier would just walk the next available edge. Instead
        // assert structurally: no path of length 3 starts with the same param
        // appearing twice (which a missing cycle guard would create).
        LiteralCommandNode<Source> rootNode = BrigadierCommandAdapter
                .toBrigadier(fixtureRoot(), permissive())
                .build();
        // Walk every path; assert no parameter name repeats.
        assertNoRepeatedParamOnAnyPath(rootNode, new ArrayList<>());
    }

    private static <S> void assertNoRepeatedParamOnAnyPath(CommandNode<S> node, List<String> path) {
        String name = node.getName();
        // Skip the root literal label from the dup-check (it's not a parameter).
        if (!path.isEmpty() && path.contains(name)) {
            fail("Brigadier tree contains a path that repeats node '" + name
                    + "': " + path + " -> " + name + " (cycle guard regression?)");
        }
        path.add(name);
        for (CommandNode<S> child : node.getChildren()) {
            assertNoRepeatedParamOnAnyPath(child, path);
        }
        path.remove(path.size() - 1);
    }

    @Test
    @DisplayName("Sub-commands are NOT attached after a parameter has been consumed")
    void subCommandNotReachableAfterParameter() {
        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(fixtureRoot(), permissive()));
        // Per the audit: sub-commands stay positional at the head of args[]; once
        // a parameter is consumed, sub-command literals are no longer reachable.
        // Structural assertion: walk the tree, find the 'a' argument node, and
        // assert it has no 'sub' child. (Brigadier strings parse positionally
        // so we can't easily distinguish a literal-after-param from a positional
        // value via dispatcher.execute; the structural check is more reliable.)
        LiteralCommandNode<Source> rootNode = BrigadierCommandAdapter
                .toBrigadier(fixtureRoot(), permissive())
                .build();
        CommandNode<Source> aNode = rootNode.getChild("a");
        assertNotNull(aNode, "argument node 'a' must exist at the root");
        assertNull(aNode.getChild("sub"),
                "sub-command literal must NOT be attached as a child of a parameter node");
    }

    @Test
    @DisplayName("Total Brigadier node count is bounded by the cycle guard")
    void treeNodeCountIsBounded() {
        LiteralCommandNode<Source> rootNode = BrigadierCommandAdapter
                .toBrigadier(fixtureRoot(), permissive())
                .build();
        int count = countNodes(rootNode);
        // Fixture: 2 top-level params (a + nested {x,y}, b) + 1 sub-command (sub + p).
        // With sibling chaining + cycle guard, the root branch enumerates permutations
        // of {a, b} (and under 'a': nested {x,y} also chained as siblings of {b}). The
        // bound below (<= 64) is generous: a regression that drops the cycle guard
        // would explode the tree well past this.
        assertTrue(count <= 64,
                "Brigadier tree node count " + count + " exceeds documented bound (<= 64); "
                        + "did the cycle guard regress?");
    }

    private static <S> int countNodes(CommandNode<S> node) {
        int n = 1;
        for (CommandNode<S> c : node.getChildren()) n += countNodes(c);
        return n;
    }

    // ------------------------------------------------------------------
    // Silent-failure isolation (commands-api-ADR-001 addendum 2026-05-06 §"Silent failure isolation")
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Throwing subcommand is skipped, sibling subcommand and base /root still register")
    void throwingSubcommandIsIsolated() {
        StubTreeCommand root = new StubTreeCommand("rtp");
        // A subcommand whose getParameterLookup() throws — simulates a
        // misbehaving plugin that NPEs while the dispatcher is being built
        // (e.g., a lazy-init parameter map blowing up because the server
        // accessor isn't bound yet on Fabric early init).
        root.addSubCommand(new StubTreeCommand("sub") {
            @Override public Map<String, CommandParameter> getParameterLookup() {
                throw new RuntimeException("boom: subcommand getParameterLookup() failure");
            }
        });
        // A well-behaved sibling subcommand that MUST still register.
        root.addSubCommand(new StubTreeCommand("ok"));

        LiteralCommandNode<Source> built = BrigadierCommandAdapter
                .toBrigadier(root, permissive()).build();
        // Base /rtp still registered; the bad subcommand is skipped; the good
        // sibling is reachable. Pre-fix this would have aborted the whole walk.
        assertNotNull(built, "root literal must still build despite a throwing subcommand");
        assertNotNull(built.getChild("ok"), "well-behaved sibling subcommand must remain reachable");
    }

    @Test
    @DisplayName("Throwing parameter is skipped, sibling parameter and base /root still register")
    void throwingParameterIsIsolated() {
        StubTreeCommand root = new StubTreeCommand("rtp");
        root.addParameter("good", new StringParam());
        // A parameter whose values() throws is fine for tree-build (only invoked
        // at suggestion time, where we already catch). Simulate a registration-
        // time throw via a CommandParameter whose subParams() throws.
        root.addParameter("bad", new StringParam() {
            @Override public Map<String, CommandParameter> subParams(String parameter) {
                throw new RuntimeException("boom: subParams() failure");
            }
        });

        LiteralCommandNode<Source> built = BrigadierCommandAdapter
                .toBrigadier(root, permissive()).build();
        assertNotNull(built, "root literal must still build despite a throwing parameter");
        assertNotNull(built.getChild("good"),
                "well-behaved sibling parameter must remain reachable");
    }

    @Test
    @DisplayName("Throwing suggestion provider does not propagate; player still sees the parameter node")
    void throwingSuggestionProviderIsIsolated() throws CommandSyntaxException {
        StubTreeCommand root = new StubTreeCommand("rtp");
        // values() throws — the SuggestionProvider lambda must catch it and
        // return an empty suggestion future rather than letting the throw kill
        // the whole tab-completion path for /rtp.
        root.addParameter("p", new StringParam() {
            @Override public Set<String> values() {
                throw new RuntimeException("boom: values() failure");
            }
        });

        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(root, permissive()));
        // The parameter node must still parse a typed value (suggestions are a
        // separate path; throwing values() must not strip the node).
        dispatcher.execute("rtp anything", new Source());
    }

    // ------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------

    /** A {@link CommandParameter} with a small fixed values() set; permissive validator. */
    private static class StringParam extends CommandParameter {
        StringParam() {
            super("", "", (uuid, s) -> true);
        }

        @Override
        public Set<String> values() {
            // Two static values are enough for suggestion plumbing; tree-shape
            // assertions don't depend on their content.
            Set<String> out = new HashSet<>();
            out.add("foo");
            out.add("bar");
            return out;
        }

        @Override
        public Map<String, CommandParameter> subParams(String parameter) {
            // Look up by the parameter's *registered* name as held in subParamMap.
            return subParamMap.get(parameter);
        }
    }

    /** Minimal {@link TreeCommand} mirroring the one in {@link ReqApiArch005BrigadierBridgeTest}. */
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
