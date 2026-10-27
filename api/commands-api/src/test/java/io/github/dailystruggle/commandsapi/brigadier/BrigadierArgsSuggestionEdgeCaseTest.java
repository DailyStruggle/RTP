package io.github.dailystruggle.commandsapi.brigadier;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-ARCH-005: Brigadier greedy-argument suggestion edge cases")
class BrigadierArgsSuggestionEdgeCaseTest {

    @Test
    @DisplayName("stage one filters null and empty keys, permissions, prefixes, and non-head subcommands")
    void stageOneFiltersKeysPermissionsAndPrefixes() throws Exception {
        StubTreeCommand root = new StubTreeCommand("rtp");
        root.parameters.put(null, new StringParameter("perm.visible", "hidden"));
        root.parameters.put("", new StringParameter(null, "hidden"));
        root.parameters.put("allowed", new StringParameter("perm.visible", "alpha", "alpine"));
        root.parameters.put("denied", new StringParameter("perm.denied", "secret"));
        root.parameters.put("available", null);
        root.addSubCommand(new StubTreeCommand("scan", "perm.visible"));
        root.addSubCommand(new StubTreeCommand("secret", "perm.denied"));
        root.subCommands.put("NULL", null);

        List<String> all = suggestionTexts(suggestions("rtp ", root, (source, permission) -> permission.equals("perm.visible")));
        assertTrue(all.containsAll(List.of("allowed=", "available=", "scan")), all.toString());
        assertFalse(all.contains("denied="), all.toString());
        assertTrue(all.contains("secret"), "Brigadier returns literal suggestions independently of canUse: " + all);
        assertFalse(all.contains("="), all.toString());

        BrigadierBridgeContext<Source> permissionContext = new BrigadierBridgeContext<>(
                source -> source.id,
                (source, permission) -> permission.equals("perm.visible"),
                (source, message) -> { });
        LiteralCommandNode<Source> built = BrigadierCommandAdapter.toBrigadier(root, permissionContext).build();
        assertFalse(built.getChild("secret").canUse(new Source()), "secret literal remains gated by its permission");

        List<String> prefix = suggestionTexts(suggestions("rtp al", root, (source, permission) -> true));
        assertEquals(List.of("allowed="), prefix);

        List<String> laterToken = suggestionTexts(suggestions("rtp previous sc", root, (source, permission) -> true));
        assertFalse(laterToken.contains("scan"), laterToken.toString());
    }

    @Test
    @DisplayName("stage two matches keys without case sensitivity and filters values by prefix")
    void stageTwoMatchesCaseInsensitiveKeyAndValuePrefix() throws Exception {
        StubTreeCommand root = new StubTreeCommand("rtp");
        root.parameters.put(null, new StringParameter(null, "unreachable"));
        root.parameters.put("Name", new StringParameter(null, "apple", "Apricot", "banana", null));
        root.parameters.put("other", new StringParameter(null, "another"));

        List<String> suggestions = suggestionTexts(suggestions("rtp nAmE=ap", root, (source, permission) -> true));

        assertTrue(suggestions.containsAll(List.of("Name=apple", "Name=Apricot")), suggestions.toString());
        assertFalse(suggestions.contains("Name=banana"), suggestions.toString());
        assertFalse(suggestions.stream().anyMatch(value -> value.endsWith("=null")), suggestions.toString());

        List<String> unknownKey = suggestionTexts(suggestions("rtp unknown=a", root, (source, permission) -> true));
        assertTrue(unknownKey.isEmpty(), unknownKey.toString());
    }

    @Test
    @DisplayName("a throwing stage-one permission predicate denies suggestions")
    void throwingPermissionPredicateDeniesSuggestions() throws Exception {
        StubTreeCommand root = new StubTreeCommand("rtp");
        root.parameters.put("private", new StringParameter("perm.private", "value"));
        root.addSubCommand(new StubTreeCommand("hidden", "perm.private"));

        List<String> suggestions = suggestionTexts(suggestions(
                "rtp ", root, (source, permission) -> {
                    throw new IllegalStateException("permission lookup unavailable");
                }));

        assertFalse(suggestions.contains("private="), suggestions.toString());

        BrigadierBridgeContext<Source> throwingContext = new BrigadierBridgeContext<>(
                source -> source.id,
                (source, permission) -> {
                    throw new IllegalStateException("permission lookup unavailable");
                },
                (source, message) -> { });
        LiteralCommandNode<Source> built = BrigadierCommandAdapter.toBrigadier(root, throwingContext).build();
        assertFalse(built.getChild("hidden").canUse(new Source()), "a throwing permission check denies the literal");
    }

    private static Suggestions suggestions(
            String input,
            StubTreeCommand root,
            BiPredicate<Source, String> permissionCheck) throws Exception {
        BrigadierBridgeContext<Source> context = new BrigadierBridgeContext<>(
                source -> source.id,
                permissionCheck,
                (source, message) -> { });
        CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        dispatcher.register(BrigadierCommandAdapter.toBrigadier(root, context));
        ParseResults<Source> parsed = dispatcher.parse(input, new Source());
        CompletableFuture<Suggestions> result = dispatcher.getCompletionSuggestions(parsed);
        return result.get();
    }

    private static List<String> suggestionTexts(Suggestions suggestions) {
        return suggestions.getList().stream().map(Suggestion::getText).toList();
    }

    private static final class Source {
        private final UUID id = new UUID(5L, 11L);
    }

    private static final class StringParameter extends CommandParameter {
        private final Set<String> values;

        StringParameter(String permission, String... values) {
            super(permission, "test parameter", (caller, value) -> true);
            this.values = new HashSet<>(java.util.Arrays.asList(values));
        }

        @Override
        public Set<String> values() {
            return values;
        }
    }

    private static final class StubTreeCommand implements TreeCommand {
        private final String name;
        private final String permission;
        private final Map<String, CommandParameter> parameters = new LinkedHashMap<>();
        private final Map<String, CommandsAPICommand> subCommands = new LinkedHashMap<>();

        StubTreeCommand(String name) {
            this(name, null);
        }

        StubTreeCommand(String name, String permission) {
            this.name = name;
            this.permission = permission;
        }

        @Override
        public Map<String, CommandParameter> getParameterLookup() {
            return parameters;
        }

        @Override
        public Map<String, CommandsAPICommand> getCommandLookup() {
            return subCommands;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String permission() {
            return permission;
        }

        @Override
        public String description() {
            return "test command";
        }

        @Override
        public CommandsAPICommand parent() {
            return null;
        }

        @Override
        public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
        }

        @Override
        public long avgTime() {
            return 0L;
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return true;
        }

        @Override
        public List<String> help(UUID callerId, Predicate<String> permissionCheckMethod) {
            return new ArrayList<>();
        }

        void addSubCommand(StubTreeCommand command) {
            subCommands.put(command.name().toUpperCase(), command);
        }
    }
}
