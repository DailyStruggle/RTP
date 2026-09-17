package io.github.dailystruggle.commandsapi.brigadier;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BrigadierCommandAdapter comprehensive edge case coverage")
class BrigadierCommandAdapterExtendedTest {

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static final class TestSource {
        final UUID id = UUID.randomUUID();
        final List<String> sentMessages = new ArrayList<>();
    }

    private static class StubCmd implements CommandsAPICommand {
        private final String name;
        private final String perm;

        StubCmd(String name, String perm) {
            this.name = name;
            this.perm = perm;
        }

        @Override public String name() { return name; }
        @Override public String permission() { return perm; }
        @Override public String description() { return "desc"; }
        @Override public CommandsAPICommand parent() { return null; }
        @Override public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {}
        @Override public void msgInvalidCommand(UUID callerId, String argument) {}
        @Override public long avgTime() { return 0L; }
        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) {
            return true;
        }

        @Override
        public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                     Predicate<String> permissionCheckMethod,
                                                     Consumer<String> messageMethod,
                                                     String[] args,
                                                     int i,
                                                     Map<String, CommandParameter> tempParameters) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public List<String> onTabComplete(UUID callerId,
                                          Predicate<String> permissionCheckMethod,
                                          String[] args,
                                          int i,
                                          Map<String, CommandParameter> tempParameters) {
            return new ArrayList<>();
        }

        @Override
        public List<String> help(UUID callerId, Predicate<String> permissionCheckMethod) {
            return new ArrayList<>();
        }
    }

    private static class StubTreeCmd extends StubCmd implements TreeCommand {
        private final Map<String, CommandParameter> params = new LinkedHashMap<>();
        private final Map<String, CommandsAPICommand> subCmds = new LinkedHashMap<>();
        CompletableFuture<Boolean> onCommandResult = CompletableFuture.completedFuture(true);
        String[] capturedArgs = null;

        StubTreeCmd(String name, String perm) {
            super(name, perm);
        }

        @Override public Map<String, CommandParameter> getParameterLookup() { return params; }
        @Override public Map<String, CommandsAPICommand> getCommandLookup() { return subCmds; }

        @Override
        public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                     Predicate<String> permissionCheckMethod,
                                                     Consumer<String> messageMethod,
                                                     String[] args,
                                                     int i,
                                                     Map<String, CommandParameter> tempParameters) {
            this.capturedArgs = args;
            messageMethod.accept("msg from " + name());
            return onCommandResult;
        }
    }

    private static class StubParam extends CommandParameter {
        private final Set<String> vals;

        StubParam(String perm, Set<String> vals) {
            super(perm, "desc", (u, s) -> true);
            this.vals = vals;
        }

        @Override
        public Set<String> values() {
            return vals;
        }

        @Override
        public Set<String> relevantValues(UUID callerId) {
            return vals;
        }
    }

    @Test
    @DisplayName("attachChildren: handles non-TreeCommand, null/empty names, and null lookups")
    void testAttachChildrenEdgeCases() {
        // 1. Root where a subcommand is not a TreeCommand
        StubTreeCmd root = new StubTreeCmd("root", "perm.root");
        StubCmd nonTree = new StubCmd("nontree", "perm.nontree");
        root.addSubCommand(nonTree);

        // Subcommand with null name or empty name should be skipped
        StubTreeCmd emptyNamed = new StubTreeCmd("", "perm.empty");
        root.getCommandLookup().put("EMPTY", emptyNamed);
        root.getCommandLookup().put("NULL_CMD", null);

        BrigadierBridgeContext<TestSource> ctx = new BrigadierBridgeContext<>(
                src -> src.id,
                (src, p) -> true,
                (src, m) -> src.sentMessages.add(m)
        );

        LiteralArgumentBuilder<TestSource> builder = BrigadierCommandAdapter.toBrigadier(root, ctx);
        LiteralCommandNode<TestSource> node = builder.build();

        assertNotNull(node.getChild("nontree"));
        assertNull(node.getChild(""));
        assertNull(node.getChild("null"));
    }

    @Test
    @DisplayName("argsSuggestionsFor: stage 2 suggestions with matching and non-matching parameter keys")
    void testStage2Suggestions() throws Exception {
        StubTreeCmd root = new StubTreeCmd("cmd", "perm");
        StubParam p1 = new StubParam("perm.p1", Set.of("valueA", "valueB"));
        root.addParameter("paramOne", p1);

        BrigadierBridgeContext<TestSource> ctx = new BrigadierBridgeContext<>(
                src -> src.id,
                (src, p) -> true,
                (src, m) -> {}
        );

        CommandDispatcher<TestSource> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(root, ctx));

        // 1. Matched parameter key with no value typed: "cmd paramOne="
        ParseResults<TestSource> parse1 = dispatcher.parse("cmd paramOne=", new TestSource());
        Suggestions s1 = dispatcher.getCompletionSuggestions(parse1).get();
        Set<String> texts1 = s1.getList().stream().map(Suggestion::getText).collect(Collectors.toSet());
        // Note: TreeCommand.addParameter lowercases the key name ("paramone")
        assertTrue(texts1.contains("paramone=valueA"), "Should suggest paramone=valueA: " + texts1);
        assertTrue(texts1.contains("paramone=valueB"), "Should suggest paramone=valueB: " + texts1);

        // 2. Matched parameter key with partial value: "cmd paramOne=val" (lowercase value prefix)
        ParseResults<TestSource> parse2 = dispatcher.parse("cmd paramOne=val", new TestSource());
        Suggestions s2 = dispatcher.getCompletionSuggestions(parse2).get();
        Set<String> texts2 = s2.getList().stream().map(Suggestion::getText).collect(Collectors.toSet());
        assertTrue(texts2.contains("paramone=valueA"));
        assertTrue(texts2.contains("paramone=valueB"));

        // 3. Unmatched parameter key: "cmd unknown=" -> should produce no value suggestions
        ParseResults<TestSource> parse3 = dispatcher.parse("cmd unknown=", new TestSource());
        Suggestions s3 = dispatcher.getCompletionSuggestions(parse3).get();
        Set<String> texts3 = s3.getList().stream().map(Suggestion::getText).collect(Collectors.toSet());
        assertTrue(texts3.isEmpty(), "Unmatched param should yield no suggestions: " + texts3);

        // 4. Multiple tokens typed before: "cmd foo=bar paramOne="
        ParseResults<TestSource> parse4 = dispatcher.parse("cmd foo=bar paramOne=", new TestSource());
        Suggestions s4 = dispatcher.getCompletionSuggestions(parse4).get();
        Set<String> texts4 = s4.getList().stream().map(Suggestion::getText).collect(Collectors.toSet());
        assertTrue(texts4.contains("paramone=valueA"));

        // 5. Parameter value with no match produces empty suggestions
        ParseResults<TestSource> parseNoMatch = dispatcher.parse("cmd paramOne=nomatch", new TestSource());
        Suggestions sNoMatch = dispatcher.getCompletionSuggestions(parseNoMatch).get();
        assertTrue(sNoMatch.getList().isEmpty());

        // 6. Unknown parameter produces empty suggestions
        ParseResults<TestSource> parseUnknown = dispatcher.parse("cmd unknown=foo", new TestSource());
        Suggestions sUnknown = dispatcher.getCompletionSuggestions(parseUnknown).get();
        assertTrue(sUnknown.getList().isEmpty());

        // 7. Suggestion matching parameter name without delimiter prefix: "cmd paramone="
        ParseResults<TestSource> parseParamNameMatch = dispatcher.parse("cmd paramone=", new TestSource());
        assertNotNull(dispatcher.getCompletionSuggestions(parseParamNameMatch).get());

        // 8. Suggestion with paramName prefix when typing key name before delimiter
        ParseResults<TestSource> parseKeyPrefix = dispatcher.parse("cmd param", new TestSource());
        Suggestions sKeyPrefix = dispatcher.getCompletionSuggestions(parseKeyPrefix).get();
        Set<String> keyPrefixTexts = sKeyPrefix.getList().stream().map(Suggestion::getText).collect(Collectors.toSet());
        assertTrue(keyPrefixTexts.contains("paramone="));
    }

    @Test
    @DisplayName("argsSuggestionsFor: permission predicate throwing exception is isolated and denied")
    void testSuggestionPermissionThrowIsIsolated() throws Exception {
        StubTreeCmd root = new StubTreeCmd("cmd", "perm");
        root.addParameter("risky", new StubParam("risky.perm", Set.of("val1")));
        root.addSubCommand(new StubTreeCmd("sub", "risky.sub"));

        BrigadierBridgeContext<TestSource> throwingCtx = new BrigadierBridgeContext<>(
                src -> src.id,
                (src, p) -> {
                    if (p.startsWith("risky")) throw new RuntimeException("permission probe failed");
                    return true;
                },
                (src, m) -> {}
        );

        CommandDispatcher<TestSource> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(root, throwingCtx));

        // When evaluating suggestions, the throwing permission predicate should not throw or break the suggestion future
        ParseResults<TestSource> parse = dispatcher.parse("cmd ", new TestSource());
        Suggestions s = dispatcher.getCompletionSuggestions(parse).get();
        Set<String> texts = s.getList().stream().map(Suggestion::getText).collect(Collectors.toSet());
        assertFalse(texts.contains("risky="));
        // Note: the literal subcommand node "sub" is present in the tree; getCompletionSuggestions returns literal completions
        // as well as greedy args suggestions. But in the greedy args slot suggestions, risky= was filtered out.
        assertFalse(texts.contains("risky=val1"));
    }

    @Test
    @DisplayName("applyRequires: throwing permission check denies command usage")
    void testApplyRequiresThrowingDenies() {
        StubTreeCmd root = new StubTreeCmd("cmd", "perm.cmd");
        StubTreeCmd sub = new StubTreeCmd("sub", "throwing.perm");
        root.addSubCommand(sub);

        BrigadierBridgeContext<TestSource> throwingCtx = new BrigadierBridgeContext<>(
                src -> src.id,
                (src, p) -> {
                    if ("throwing.perm".equals(p)) throw new RuntimeException("boom");
                    return true;
                },
                (src, m) -> {}
        );

        LiteralCommandNode<TestSource> node = BrigadierCommandAdapter.toBrigadier(root, throwingCtx).build();
        assertFalse(node.getChild("sub").canUse(new TestSource()));
    }

    @Test
    @DisplayName("execute: reconstructArgs multiple whitespace, empty args, and return code 0 on false")
    void testExecuteReconstructArgsAndReturnCode() throws Exception {
        StubTreeCmd root = new StubTreeCmd("cmd", "perm.cmd");
        root.onCommandResult = CompletableFuture.completedFuture(false);

        TestSource src = new TestSource();
        BrigadierBridgeContext<TestSource> ctx = new BrigadierBridgeContext<>(
                s -> s.id,
                (s, p) -> true,
                (s, m) -> s.sentMessages.add(m)
        );

        CommandDispatcher<TestSource> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(root, ctx));

        // Execute with extra whitespace and tabs in greedy string
        int exitCode = dispatcher.execute("cmd   param1=val1   param2=val2\tparam3=val3  ", src);
        assertEquals(0, exitCode, "Return code should be 0 when onCommand returns false");
        assertNotNull(root.capturedArgs);
        assertEquals(3, root.capturedArgs.length);
        assertEquals("param1=val1", root.capturedArgs[0]);
        assertEquals("param2=val2", root.capturedArgs[1]);
        assertEquals("param3=val3", root.capturedArgs[2]);
        assertTrue(src.sentMessages.contains("msg from cmd"));

        // Bare literal with no args
        root.onCommandResult = CompletableFuture.completedFuture(true);
        int exitCodeBare = dispatcher.execute("cmd", src);
        assertEquals(1, exitCodeBare);
        assertEquals(0, root.capturedArgs.length);
    }

    @Test
    @DisplayName("execute: target onCommand throwing exception propagates out")
    void testExecuteThrowsPropagate() {
        StubTreeCmd root = new StubTreeCmd("cmd", "perm.cmd") {
            @Override
            public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                         Predicate<String> permissionCheckMethod,
                                                         Consumer<String> messageMethod,
                                                         String[] args,
                                                         int i,
                                                         Map<String, CommandParameter> tempParameters) {
                throw new IllegalStateException("fatal error in command");
            }
        };

        TestSource src = new TestSource();
        BrigadierBridgeContext<TestSource> ctx = new BrigadierBridgeContext<>(
                s -> s.id,
                (s, p) -> true,
                (s, m) -> {}
        );

        CommandDispatcher<TestSource> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(root, ctx));

        assertThrows(IllegalStateException.class, () -> dispatcher.execute("cmd", src));
    }

    @Test
    @DisplayName("edge cases: non-tree commands, null map entries, null return in execute")
    void testBrigadierAdapterEdgeCases() throws Exception {
        // 1. Non-TreeCommand root
        StubCmd nonTreeRoot = new StubCmd("bare", null);
        TestSource src = new TestSource();
        BrigadierBridgeContext<TestSource> ctx = new BrigadierBridgeContext<>(
                s -> s.id,
                (s, p) -> true,
                (s, m) -> {}
        );
        CommandDispatcher<TestSource> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(nonTreeRoot, ctx));

        // Suggestions on non-tree root
        ParseResults<TestSource> parseNonTree = dispatcher.parse("bare ", src);
        Suggestions suggestions = dispatcher.getCompletionSuggestions(parseNonTree).get();
        assertTrue(suggestions.isEmpty());

        // 2. Tree command with null parameters and null subcommands map entries
        StubTreeCmd tree = new StubTreeCmd("tree", "");
        tree.getParameterLookup().put("nullparam", null);
        tree.getParameterLookup().put("", new StubParam(null, Set.of("val")));
        tree.getCommandLookup().put("nullcmd", null);
        tree.getCommandLookup().put("emptyname", new StubCmd("", null));

        // Subcommand that throws during attach
        tree.getCommandLookup().put("faulty", new StubCmd("faulty", null) {
            @Override
            public String name() {
                throw new RuntimeException("subcommand explosion");
            }
        });

        CommandDispatcher<TestSource> dispatcher2 = new CommandDispatcher<>();
        dispatcher2.register(BrigadierCommandAdapter.toBrigadier(tree, ctx));

        // Suggestions should handle null/empty gracefully
        ParseResults<TestSource> parseTree = dispatcher2.parse("tree ", src);
        Suggestions sTree = dispatcher2.getCompletionSuggestions(parseTree).get();
        assertNotNull(sTree);

        // 3. onCommand returning null future (returns 1)
        StubTreeCmd nullResultTree = new StubTreeCmd("nullres", null);
        nullResultTree.onCommandResult = null;
        CommandDispatcher<TestSource> dispatcher3 = new CommandDispatcher<>();
        dispatcher3.register(BrigadierCommandAdapter.toBrigadier(nullResultTree, ctx));
        int code = dispatcher3.execute("nullres", src);
        assertEquals(1, code);

        // 4. Parameter relevantValues containing null or empty
        StubTreeCmd paramTree = new StubTreeCmd("ptree", null);
        paramTree.addParameter("p", new CommandParameter(null, "desc", (u, str) -> true) {
            @Override
            public Set<String> values() {
                Set<String> set = new HashSet<>();
                set.add(null);
                set.add("goodVal");
                return set;
            }
        });
        CommandDispatcher<TestSource> dispatcher4 = new CommandDispatcher<>();
        dispatcher4.register(BrigadierCommandAdapter.toBrigadier(paramTree, ctx));
        ParseResults<TestSource> parseParam = dispatcher4.parse("ptree p=", src);
        Suggestions sParam = dispatcher4.getCompletionSuggestions(parseParam).get();
        Set<String> paramTexts = sParam.getList().stream().map(Suggestion::getText).collect(Collectors.toSet());
        assertTrue(paramTexts.contains("p=goodVal"));

        // Direct execution on non-tree leaf command
        dispatcher.execute("bare", src);

        // 5. Suggestions when permission check predicate throws an exception
        BrigadierBridgeContext<TestSource> throwingCtx = new BrigadierBridgeContext<>(
                s -> s.id,
                (s, perm) -> { throw new RuntimeException("permission check failure"); },
                (s, msg) -> {}
        );
        CommandDispatcher<TestSource> throwingDispatcher = new CommandDispatcher<>();
        throwingDispatcher.register(BrigadierCommandAdapter.toBrigadier(paramTree, throwingCtx));
        ParseResults<TestSource> throwingParse = throwingDispatcher.parse("ptree ", src);
        assertNotNull(throwingDispatcher.getCompletionSuggestions(throwingParse).get());
    }
}
