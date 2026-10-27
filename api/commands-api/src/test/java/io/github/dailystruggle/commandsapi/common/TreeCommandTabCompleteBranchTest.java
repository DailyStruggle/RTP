package io.github.dailystruggle.commandsapi.common;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-005: TreeCommand tab completion and command branch coverage")
class TreeCommandTabCompleteBranchTest {

    private static final UUID CALLER = new UUID(3L, 7L);

    @Test
    @DisplayName("delegated leaf completion skips nulls and filters parameters and subcommands by permission")
    void delegatedLeafCompletionFiltersNullsAndPermissions() {
        TestTreeCommand root = new TestTreeCommand("root", null);
        TestTreeCommand child = new TestTreeCommand("child", null);
        root.addSubCommand(child);

        child.parameters.put(null, new ValueParameter("perm.allowed", "hidden null key"));
        child.parameters.put("childNullValue", null);
        child.parameters.put("childOpen", new ValueParameter(null, "open"));
        child.parameters.put("childAllowed", new ValueParameter("perm.allowed", "allowed"));
        child.parameters.put("childDenied", new ValueParameter("perm.denied", "denied"));

        child.addSubCommand(new TestTreeCommand("childSubAllowed", "perm.allowed"));
        child.addSubCommand(new TestTreeCommand("childSubDenied", "perm.denied"));

        Map<String, CommandParameter> tempParameters = new LinkedHashMap<>();
        tempParameters.put(null, new ValueParameter("perm.allowed", "hidden temp null key"));
        tempParameters.put("childTempNullValue", null);
        tempParameters.put("childTempOpen", new ValueParameter(null, "open"));
        tempParameters.put("childTempAllowed", new ValueParameter("perm.allowed", "allowed"));
        tempParameters.put("childTempDenied", new ValueParameter("perm.denied", "denied"));

        List<String> suggestions = root.onTabComplete(
                CALLER,
                permission -> permission == null || permission.equals("perm.allowed"),
                new String[]{"child"},
                0,
                tempParameters);

        assertTrue(suggestions.containsAll(List.of(
                "childOpen=", "childAllowed=", "childTempOpen=", "childTempAllowed=", "childSubAllowed")),
                suggestions.toString());
        assertFalse(suggestions.contains("childDenied="), suggestions.toString());
        assertFalse(suggestions.contains("childTempDenied="), suggestions.toString());
        assertFalse(suggestions.contains("childSubDenied"), suggestions.toString());
        assertFalse(suggestions.contains("childNullValue="), suggestions.toString());
        assertFalse(suggestions.contains("childTempNullValue="), suggestions.toString());
    }

    @Test
    @DisplayName("empty and non-last completion skip null map entries and denied permissions")
    void emptyAndNonLastCompletionFilterNullsAndPermissions() {
        TestTreeCommand root = new TestTreeCommand("root", null);
        root.parameters.put(null, new ValueParameter(null, "hidden null key"));
        root.parameters.put("nullValue", null);
        root.parameters.put("open", new ValueParameter(null, "open"));
        root.parameters.put("allowed", new ValueParameter("perm.allowed", "allowed"));
        root.parameters.put("denied", new ValueParameter("perm.denied", "denied"));
        root.addSubCommand(new TestTreeCommand("allowedSub", null));
        root.addSubCommand(new TestTreeCommand("deniedSub", "perm.denied"));

        Predicate<String> allowSelected = permission -> permission == null || permission.equals("perm.allowed");
        List<String> onlyValue = root.onTabComplete(CALLER, allowSelected, new String[0]);
        assertTrue(onlyValue.containsAll(List.of("open=", "allowed=", "allowedSub")), onlyValue.toString());
        assertFalse(onlyValue.contains("denied="), onlyValue.toString());
        assertFalse(onlyValue.contains("deniedSub"), onlyValue.toString());

        List<String> nonLast = root.onTabComplete(CALLER, allowSelected, new String[]{"unknown", ""});
        assertTrue(nonLast.containsAll(List.of("open=", "allowed=", "allowedSub")), nonLast.toString());
        assertFalse(nonLast.contains("denied="), nonLast.toString());
        assertFalse(nonLast.contains("deniedSub"), nonLast.toString());
    }

    @Test
    @DisplayName("a temporary parameter with no nested parameters still offers its values")
    void temporaryParameterWithoutSubparametersOffersValues() {
        Map<String, CommandParameter> tempParameters = Map.of(
                "extra", new ValueParameter(null, "first", "second"));
        TestTreeCommand root = new TestTreeCommand("root", null);

        List<String> suggestions = root.onTabComplete(
                CALLER, permission -> true, new String[]{"extra="}, 0, tempParameters);

        assertTrue(suggestions.containsAll(List.of("extra=first", "extra=second")), suggestions.toString());
    }

    @Test
    @DisplayName("help omits subcommands and parameters denied by the permission predicate")
    void helpFiltersDeniedEntries() {
        TestTreeCommand root = new TestTreeCommand("root", null);
        root.addSubCommand(new TestTreeCommand("visible", "perm.visible"));
        root.addSubCommand(new TestTreeCommand("secret", "perm.secret"));
        root.addParameter("open", new ValueParameter(null, "open"));
        root.addParameter("restricted", new ValueParameter("perm.secret", "restricted"));

        List<String> help = root.help(CALLER, permission -> permission == null || permission.equals("perm.visible"));

        assertTrue(help.stream().anyMatch(line -> line.contains("visible")), help.toString());
        assertTrue(help.stream().anyMatch(line -> line.contains("open=")), help.toString());
        assertFalse(help.stream().anyMatch(line -> line.contains("secret")), help.toString());
        assertFalse(help.stream().anyMatch(line -> line.contains("restricted=")), help.toString());
    }

    @Test
    @DisplayName("a failed parent command does not invoke its subcommand continuation")
    void failedParentDoesNotContinueToSubcommand() {
        TestTreeCommand root = new TestTreeCommand("root", null);
        TestTreeCommand child = new TestTreeCommand("child", null);
        root.commandResult = false;
        root.addSubCommand(child);

        CompletableFuture<Boolean> result = root.onCommand(CALLER, permission -> true, message -> { }, new String[]{"child"});
        try {
            assertEquals(0L, CommandsAPI.execute());

            assertEquals(1, root.executionCount);
            assertEquals(0, child.executionCount);
            assertFalse(result.isDone(), "the subcommand continuation should not complete after parent failure");
        } finally {
            while (CommandsAPI.execute() != 0L) {
                // Drain any queued parent work so static command state cannot leak to other tests.
            }
        }
    }

    @Test
    @DisplayName("nested parameter parsing handles a null permission and a top-level key outside the nested map")
    void nestedParameterParsingAllowsNullPermissionAndSkipsUnmatchedNestedKey() {
        TestTreeCommand root = new TestTreeCommand("root", null);
        ValueParameter parent = new ValueParameter(null, "valid");
        ValueParameter nested = new ValueParameter(null, "ok");
        parent.subParamMap.put("valid", Map.of("child", nested));
        root.addParameter("parent", parent);
        root.addParameter("outside", new ValueParameter(null, "value"));

        CompletableFuture<Boolean> result = root.onCommand(
                CALLER,
                Objects::isNull,
                message -> { },
                new String[]{"parent=valid", "outside=value", "child=ok"});

        assertTrue(result.join());
        assertEquals(List.of("valid"), root.lastParameterValues.get("parent"));
        assertEquals(List.of("value"), root.lastParameterValues.get("outside"));
        assertEquals(List.of("ok"), root.lastParameterValues.get("child"));
    }

    private static final class ValueParameter extends CommandParameter {
        private final Set<String> values;

        ValueParameter(String permission, String... values) {
            super(permission, "test parameter", (caller, value) -> Set.of(values).contains(value));
            this.values = Set.of(values);
        }

        @Override
        public Set<String> values() {
            return values;
        }
    }

    private static final class TestTreeCommand implements TreeCommand {
        private final String name;
        private final String permission;
        private final Map<String, CommandParameter> parameters = new LinkedHashMap<>();
        private final Map<String, CommandsAPICommand> subCommands = new LinkedHashMap<>();
        private Map<String, List<String>> lastParameterValues = Map.of();
        private boolean commandResult = true;
        private int executionCount;

        TestTreeCommand(String name, String permission) {
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
            return "description for " + name;
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
            executionCount++;
            lastParameterValues = parameterValues;
            return commandResult;
        }

        @Override
        public void addSubCommand(CommandsAPICommand command) {
            subCommands.put(command.name().toUpperCase(), command);
        }
    }
}
