package io.github.dailystruggle.commandsapi.brigadier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-API-ARCH-005 — Brigadier boundary (commands-api-ADR-001).
 *
 * Verifies the Brigadier bridge produced by {@link BrigadierCommandAdapter}:
 *  - emits literal nodes for sub-commands,
 *  - emits a typed argument node for an {@link IntegerParameter},
 *  - delegates execution back to the originating commands-api command,
 *  - routes user-facing messages through the {@link BrigadierBridgeContext}.
 */
@DisplayName("REQ-API-ARCH-005 — Brigadier bridge converts commands-api tree to Brigadier nodes")
class ReqApiArch005BrigadierBridgeTest {

    /** Stub source the test dispatcher will hand to executors and predicates. */
    private static final class Source {
        final UUID id = new UUID(1L, 2L);
        final List<String> messages = new ArrayList<>();
    }

    @Test
    @DisplayName("toBrigadier emits root literal, sub-literal, and the unified greedy `args` slot (no per-parameter Brigadier nodes)")
    void emitsExpectedNodeStructure() {
        TreeCommand root = new StubTreeCommand("rtp", "");
        TreeCommand reload = new StubTreeCommand("reload", "rtp.reload");
        root.addSubCommand(reload);
        root.addParameter("count", new IntegerParameter("", "count", (uuid, s) -> true));

        BrigadierBridgeContext<Source> ctx = new BrigadierBridgeContext<>(
                src -> src.id,
                (src, perm) -> true,
                (src, msg) -> src.messages.add(msg));

        LiteralArgumentBuilder<Source> rootBuilder = BrigadierCommandAdapter.toBrigadier(root, ctx);
        LiteralCommandNode<Source> rootNode = rootBuilder.build();

        assertEquals("rtp", rootNode.getName(), "root literal name");

        Map<String, ? extends CommandNode<Source>> children = mapByName(rootNode.getChildren());
        // commands-api-ADR-001 addendum 2026-05-06e ("Option A" / vanilla-only
        // greedy slot): per-parameter Brigadier nodes (e.g. 'count') are no
        // longer emitted. Parameters live behind a single greedy `args` slot
        // and are tokenised server-side. The expected children are therefore:
        //   - 'reload' (sub-command literal), and
        //   - 'args' (RequiredArgument with greedyString() ArgumentType).
        // A regression that re-introduced a custom ArgumentType per parameter
        // would re-add a 'count' node here AND \u2014 critically \u2014 re-trigger the
        // vanilla-client kick on Fabric.
        assertTrue(children.containsKey("reload"), "expected literal sub-command 'reload'");
        assertTrue(children.containsKey("args"),
                "expected greedy 'args' parameter slot at the root");
        assertFalse(children.containsKey("count"),
                "per-parameter Brigadier node 'count' must NOT exist \u2014 parameters live behind the greedy args slot");

        // 'args' must be a typed argument node, not a literal.
        CommandNode<Source> argsNode = children.get("args");
        assertEquals("args", argsNode.getName());
        assertFalse(argsNode instanceof LiteralCommandNode,
                "'args' should be a RequiredArgumentBuilder node, not a literal");
    }

    @Test
    @DisplayName("Brigadier dispatch routes execution back to commands-api onCommand")
    void brigadierDispatchInvokesCommandsApiExecution() throws CommandSyntaxException {
        AtomicReference<String[]> capturedArgs = new AtomicReference<>();
        AtomicReference<UUID> capturedCaller = new AtomicReference<>();

        StubTreeCommand root = new StubTreeCommand("rtp", "") {
            @Override
            public CompletableFuture<Boolean> onCommand(java.util.UUID callerId,
                                                       Predicate<String> permissionCheckMethod,
                                                       Consumer<String> messageMethod,
                                                       String[] args,
                                                       int i,
                                                       Map<String, CommandParameter> tempParameters) {
                capturedCaller.set(callerId);
                capturedArgs.set(args);
                messageMethod.accept("ok");
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
        };
        StubTreeCommand reload = new StubTreeCommand("reload", "");
        root.addSubCommand(reload);

        Source source = new Source();
        BrigadierBridgeContext<Source> ctx = new BrigadierBridgeContext<>(
                src -> src.id,
                (src, perm) -> true,
                (src, msg) -> src.messages.add(msg));

        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(root, ctx));

        // Execute the root literal — root's stub onCommand should fire.
        int result = dispatcher.execute("rtp", source);
        assertEquals(1, result, "Brigadier should report success on commands-api TRUE result");
        assertEquals(source.id, capturedCaller.get(), "caller UUID propagated through bridge");
        assertNotNull(capturedArgs.get(), "args[] reconstructed by adapter");
        // Root execution path → no extra slots beyond the implicit literal.
        assertEquals(0, capturedArgs.get().length, "root path produces empty args[] (literal not duplicated)");
        assertTrue(source.messages.contains("ok"), "messageMethod routed through BrigadierBridgeContext");
    }

    @Test
    @DisplayName("Sub-command dispatch routes through the ROOT's TreeCommand.onCommand so parent pre-processing (queued CommandExecutor) is preserved (regression: Brigadier path must match Bukkit dispatcher semantics, not skip the parent)")
    void subCommandDispatchRoutesThroughRootForParity() throws CommandSyntaxException {
        AtomicReference<String[]> subArgs = new AtomicReference<>();
        AtomicReference<Integer> subIndex = new AtomicReference<>();

        // Root uses the DEFAULT TreeCommand.onCommand -- it must recursively
        // descend into the sub-command exactly as the Bukkit dispatcher does.
        StubTreeCommand root = new StubTreeCommand("rtp", "");
        StubTreeCommand info = new StubTreeCommand("info", "") {
            @Override
            public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                       Predicate<String> permissionCheckMethod,
                                                       Consumer<String> messageMethod,
                                                       String[] args, int i,
                                                       Map<String, CommandParameter> tempParameters) {
                subArgs.set(args);
                subIndex.set(i);
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
        };
        root.addSubCommand(info);

        Source source = new Source();
        BrigadierBridgeContext<Source> ctx = new BrigadierBridgeContext<>(
                src -> src.id,
                (src, perm) -> true,
                (src, msg) -> src.messages.add(msg));

        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(root, ctx));

        int result = dispatcher.execute("rtp info", source);
        assertEquals(1, result);
        // Drain commands-api's tick-driven pipeline so the queued parent
        // CommandExecutor fires and the recursion into the sub-command
        // completes (this mirrors what the platform tick loop does at
        // runtime). The continuation that invokes the sub-command runs via
        // CompletableFuture.whenCompleteAsync on the common ForkJoin pool,
        // so we busy-wait briefly for it to fire.
        io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (subArgs.get() == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        // The Brigadier bridge MUST hand args=["info"] to the root's
        // TreeCommand.onCommand so the standard recursion fires:
        //   1. root.onCommand sees "info", queues a CommandExecutor on the
        //      commands-api pipeline for any parent-level pre-processing,
        //   2. then invokes info.onCommand(args, i=1).
        // Skipping step 1 would break parity with the Bukkit dispatcher and
        // drop side-effects that depend on the parent running first.
        assertNotNull(subArgs.get(),
                "sub-command must be invoked via the root's recursive TreeCommand.onCommand");
        assertArrayEquals(new String[]{"info"}, subArgs.get(),
                "args[] passed to sub-command must include the literal 'info' at index 0 (Bukkit-parity wire format)");
        assertEquals(1, subIndex.get().intValue(),
                "sub-command's onCommand must be entered with i=1 (post-literal cursor)");
    }

    @Test
    @DisplayName("Parameter dispatch reconstructs args as 'name=value' (commands-api wire format)")
    void parameterDispatchReconstructsWireFormat() throws CommandSyntaxException {
        AtomicReference<String[]> capturedArgs = new AtomicReference<>();

        StubTreeCommand root = new StubTreeCommand("rtp", "") {
            @Override
            public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                       Predicate<String> permissionCheckMethod,
                                                       Consumer<String> messageMethod,
                                                       String[] args, int i,
                                                       Map<String, CommandParameter> tempParameters) {
                capturedArgs.set(args);
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
        };
        root.addParameter("count", new IntegerParameter("", "count", (uuid, s) -> true));

        Source source = new Source();
        BrigadierBridgeContext<Source> ctx = new BrigadierBridgeContext<>(
                src -> src.id,
                (src, perm) -> true,
                (src, msg) -> src.messages.add(msg));

        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(root, ctx));

        dispatcher.execute("rtp count=7", source);
        assertNotNull(capturedArgs.get());
        assertEquals(1, capturedArgs.get().length);
        assertEquals("count=7", capturedArgs.get()[0],
                "parameter args must be reconstructed in commands-api 'name=value' wire format");
    }

    @Test
    @DisplayName("Permission predicate is wired through requires(...)")
    void permissionPredicateIsWired() {
        StubTreeCommand root = new StubTreeCommand("rtp", "");
        StubTreeCommand admin = new StubTreeCommand("admin", "rtp.admin");
        root.addSubCommand(admin);

        // Permission check returns false → the admin node must be filtered out.
        BrigadierBridgeContext<Source> ctx = new BrigadierBridgeContext<>(
                src -> src.id,
                (src, perm) -> false,
                (src, msg) -> src.messages.add(msg));

        LiteralCommandNode<Source> rootNode = BrigadierCommandAdapter.toBrigadier(root, ctx).build();
        Source source = new Source();
        CommandNode<Source> adminNode = rootNode.getChild("admin");
        assertNotNull(adminNode, "node still present in tree");
        assertFalse(adminNode.canUse(source), "permission predicate gates the node");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static <S> Map<String, CommandNode<S>> mapByName(Iterable<? extends CommandNode<S>> nodes) {
        Map<String, CommandNode<S>> out = new HashMap<>();
        for (CommandNode<S> n : nodes) out.put(n.getName(), n);
        return out;
    }

    /** Minimal {@link TreeCommand} carrying name, permission, and live param/sub-command maps. */
    private static class StubTreeCommand implements TreeCommand {
        private final String name;
        private final String permission;
        private final Map<String, CommandParameter> parameters = new HashMap<>();
        private final Map<String, CommandsAPICommand> subCommands = new HashMap<>();

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
            return List.of();
        }
    }
}
