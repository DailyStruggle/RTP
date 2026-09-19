package io.github.dailystruggle.commandsapi.common;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TreeCommand comprehensive tab-completion, execution, regex, and subparameter mechanics")
class TreeCommandExtendedTest {

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static class TestTreeCmd implements TreeCommand {
        private final String name;
        private final String perm;
        private final CommandsAPICommand parent;
        private final Map<String, CommandParameter> params = new LinkedHashMap<>();
        private final Map<String, CommandsAPICommand> subCommands = new LinkedHashMap<>();

        Map<String, List<String>> lastParamValues = null;
        List<String> badParams = new ArrayList<>();
        List<String> invalidCmds = new ArrayList<>();
        List<String> noPerms = new ArrayList<>();
        boolean executed = false;

        TestTreeCmd(String name, String perm) {
            this(name, perm, null);
        }

        TestTreeCmd(String name, String perm, CommandsAPICommand parent) {
            this.name = name;
            this.perm = perm;
            this.parent = parent;
        }

        @Override
        public Map<String, CommandParameter> getParameterLookup() {
            return params;
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
            return perm;
        }

        @Override
        public String description() {
            return "desc for " + name;
        }

        @Override
        public CommandsAPICommand parent() {
            return parent;
        }

        @Override
        public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
            badParams.add(parameterName + "=" + parameterValue);
        }

        @Override
        public void msgBadParameter(UUID callerId, String parameterName, String parameterValue, Consumer<String> messageMethod) {
            msgBadParameter(callerId, parameterName, parameterValue);
            messageMethod.accept("badParam:" + parameterName + "=" + parameterValue);
        }

        @Override
        public void msgInvalidCommand(UUID callerId, String argument) {
            invalidCmds.add(argument);
        }

        @Override
        public void msgInvalidCommand(UUID callerId, String argument, Consumer<String> messageMethod) {
            msgInvalidCommand(callerId, argument);
            messageMethod.accept("invalidCmd:" + argument);
        }

        @Override
        public void msgNoPermission(UUID callerId, String permission) {
            noPerms.add(permission);
        }

        @Override
        public void msgNoPermission(UUID callerId, String permission, Consumer<String> messageMethod) {
            msgNoPermission(callerId, permission);
            messageMethod.accept("noPerm:" + permission);
        }

        @Override
        public long avgTime() {
            return 10L;
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            this.lastParamValues = parameterValues;
            this.executed = true;
            return true;
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand, Consumer<String> messageMethod) {
            this.lastParamValues = parameterValues;
            this.executed = true;
            messageMethod.accept("exec:" + name);
            return true;
        }
    }

    private static class DynamicParam extends CommandParameter {
        private final Set<String> vals;

        DynamicParam(String perm, String desc, Set<String> vals) {
            super(perm, desc, (uuid, s) -> vals.contains(s));
            this.vals = vals;
        }

        DynamicParam(String perm, String desc, Set<String> vals, Predicate<String> validator) {
            super(perm, desc, (uuid, s) -> validator.test(s));
            this.vals = vals;
        }

        @Override
        public Set<String> values() {
            return vals;
        }

        @Override
        public Map<String, CommandParameter> subParams(String parameter) {
            return subParamMap.get(parameter);
        }
    }

    @Test
    @DisplayName("splitOnParamDelimiter unit tests")
    void testSplitOnParamDelimiter() {
        String[] res1 = TreeCommand.splitOnParamDelimiter("foo=bar");
        assertEquals(2, res1.length);
        assertEquals("foo", res1[0]);
        assertEquals("bar", res1[1]);

        String[] res2 = TreeCommand.splitOnParamDelimiter("foo=");
        assertEquals(2, res2.length);
        assertEquals("foo", res2[0]);
        assertEquals("", res2[1]);

        String[] res3 = TreeCommand.splitOnParamDelimiter("plain");
        assertEquals(1, res3.length);
        assertEquals("plain", res3[0]);

        String[] res4 = TreeCommand.splitOnParamDelimiter("a=b=c");
        assertEquals(2, res4.length);
        assertEquals("a", res4[0]);
        assertEquals("b=c", res4[1]);
    }

    @Test
    @DisplayName("expandRegexToken unit tests")
    void testExpandRegexToken() {
        UUID caller = UUID.randomUUID();
        DynamicParam param = new DynamicParam("perm", "desc", Set.of("apple", "banana", "apricot", "cherry"));

        // null token
        assertEquals(0, TreeCommand.expandRegexToken(null, param, caller).count());

        // non-regex token
        List<String> literal = TreeCommand.expandRegexToken("apple", param, caller).collect(Collectors.toList());
        assertEquals(List.of("apple"), literal);

        // malformed regex -> treated as literal
        List<String> malformed = TreeCommand.expandRegexToken("reg:[invalid", param, caller).collect(Collectors.toList());
        assertEquals(List.of("reg:[invalid"), malformed);

        // valid regex matching multiple
        List<String> matched = TreeCommand.expandRegexToken("reg:ap.*", param, caller).sorted().collect(Collectors.toList());
        assertEquals(List.of("apple", "apricot"), matched);

        // valid regex matching none
        List<String> none = TreeCommand.expandRegexToken("reg:z.*", param, caller).collect(Collectors.toList());
        assertTrue(none.isEmpty());

        // regex expansion filtered by isRelevant validator
        DynamicParam restrictedParam = new DynamicParam("perm", "desc", Set.of("apple", "banana", "apricot"), s -> !s.equals("apple"));
        List<String> restricted = TreeCommand.expandRegexToken("reg:ap.*", restrictedParam, caller).collect(Collectors.toList());
        assertEquals(List.of("apricot"), restricted);
    }

    @Test
    @DisplayName("onTabComplete: intermediate parameter with subparameters, commas, and chaining")
    void testTabCompleteWithSubparameters() {
        UUID caller = UUID.randomUUID();
        TestTreeCmd root = new TestTreeCmd("root", "perm.root");

        DynamicParam shapeParam = new DynamicParam("perm.shape", "Shape", Set.of("circle", "square"));
        DynamicParam radiusParam = new DynamicParam("perm.radius", "Radius", Set.of("10", "20", "30"));
        DynamicParam widthParam = new DynamicParam("perm.width", "Width", Set.of("50", "100"));

        shapeParam.subParamMap.put("circle", Map.of("radius", radiusParam));
        shapeParam.subParamMap.put("square", Map.of("width", widthParam));

        root.addParameter("shape", shapeParam);

        // 1. Initial tab completion on empty string
        List<String> tab0 = root.onTabComplete(caller, p -> true, new String[]{""});
        assertTrue(tab0.contains("shape="));
        assertTrue(tab0.contains("help"));

        // 2. Tab complete shape=
        List<String> tabShapeValues = root.onTabComplete(caller, p -> true, new String[]{"shape="});
        assertTrue(tabShapeValues.contains("shape=circle"));
        assertTrue(tabShapeValues.contains("shape=square"));

        // 3. Tab complete comma-separated values shape=circle,
        List<String> tabComma = root.onTabComplete(caller, p -> true, new String[]{"shape=circle,"});
        assertTrue(tabComma.contains("shape=circle,square"));

        // 4. Tab complete next arg when previous arg had sub-parameters: args=["shape=circle", ""]
        List<String> tabNext = root.onTabComplete(caller, p -> true, new String[]{"shape=circle", ""});
        assertTrue(tabNext.contains("radius="), "radius= should be offered because shape=circle unlocks radius subparameter: " + tabNext);
        assertFalse(tabNext.contains("width="), "width= should not be offered for circle: " + tabNext);

        // 5. Tab complete comma-separated subparameters: args=["shape=circle,square", ""]
        List<String> tabMultiSub = root.onTabComplete(caller, p -> true, new String[]{"shape=circle,square", ""});
        assertTrue(tabMultiSub.contains("radius="));
        assertTrue(tabMultiSub.contains("width="));

        // 6. Tab complete inside the subparameter value: args=["shape=circle", "radius="]
        List<String> tabRadiusVal = root.onTabComplete(caller, p -> true, new String[]{"shape=circle", "radius="});
        assertTrue(tabRadiusVal.contains("radius=10"));
        assertTrue(tabRadiusVal.contains("radius=20"));
        assertTrue(tabRadiusVal.contains("radius=30"));

        // 7. Tab complete comma-separated inside subparameter: args=["shape=circle", "radius=10,"]
        List<String> tabRadiusComma = root.onTabComplete(caller, p -> true, new String[]{"shape=circle", "radius=10,"});
        assertTrue(tabRadiusComma.contains("radius=10,20"));
        assertTrue(tabRadiusComma.contains("radius=10,30"));
        assertFalse(tabRadiusComma.contains("radius=10,10"));

        // 8. If parameter is already used in previous tokens, it shouldn't be suggested again
        List<String> tabAlreadyUsed = root.onTabComplete(caller, p -> true, new String[]{"shape=circle", "radius=10", ""});
        assertFalse(tabAlreadyUsed.contains("shape="));
        assertFalse(tabAlreadyUsed.contains("radius="));
    }

    @Test
    @DisplayName("onTabComplete: permission gating on parameters and subcommands")
    void testTabCompletePermissions() {
        UUID caller = UUID.randomUUID();
        TestTreeCmd root = new TestTreeCmd("root", "perm.root");

        DynamicParam openParam = new DynamicParam(null, "open param", Set.of("v1"));
        DynamicParam lockedParam = new DynamicParam("perm.locked", "locked param", Set.of("v2"));

        TestTreeCmd openSub = new TestTreeCmd("opensub", null);
        TestTreeCmd lockedSub = new TestTreeCmd("lockedsub", "perm.sub");

        root.addParameter("open", openParam);
        root.addParameter("locked", lockedParam);
        root.addSubCommand(openSub);
        root.addSubCommand(lockedSub);

        Predicate<String> denyLocked = perm -> perm == null || !perm.equals("perm.locked") && !perm.equals("perm.sub");

        List<String> suggestions = root.onTabComplete(caller, denyLocked, new String[]{""});
        assertTrue(suggestions.contains("open="));
        assertTrue(suggestions.contains("opensub"));
        assertFalse(suggestions.contains("locked="));
        assertFalse(suggestions.contains("lockedsub"));

        // When permission check allows
        List<String> allSuggestions = root.onTabComplete(caller, p -> true, new String[]{""});
        assertTrue(allSuggestions.contains("locked="));
        assertTrue(allSuggestions.contains("lockedsub"));
    }

    @Test
    @DisplayName("onTabComplete: subcommands in non-last position delegate to subcommand onTabComplete")
    void testTabCompleteSubcommandDelegation() {
        UUID caller = UUID.randomUUID();
        TestTreeCmd root = new TestTreeCmd("root", "perm.root");
        TestTreeCmd child = new TestTreeCmd("child", "perm.child");

        DynamicParam childParam = new DynamicParam("perm.childparam", "Child Param", Set.of("valA", "valB"));
        child.addParameter("cparam", childParam);
        root.addSubCommand(child);

        List<String> childTab = root.onTabComplete(caller, p -> true, new String[]{"child", ""});
        assertTrue(childTab.contains("cparam="));
        assertTrue(childTab.contains("help"));

        List<String> childParamTab = root.onTabComplete(caller, p -> true, new String[]{"child", "cparam="});
        assertTrue(childParamTab.contains("cparam=valA"));
        assertTrue(childParamTab.contains("cparam=valB"));
    }

    @Test
    @DisplayName("onCommand: regex expansion and validation")
    void testOnCommandRegexExpansion() {
        UUID caller = UUID.randomUUID();
        TestTreeCmd root = new TestTreeCmd("root", "perm.root");

        DynamicParam param = new DynamicParam("perm.p", "param", Set.of("north", "south", "east", "west"));
        root.addParameter("dir", param);

        List<String> msgs = new ArrayList<>();
        CompletableFuture<Boolean> res = root.onCommand(caller, p -> true, msgs::add, new String[]{"dir=reg:.*th"});
        assertTrue(res.join());
        assertNotNull(root.lastParamValues);
        List<String> dirs = root.lastParamValues.get("dir");
        assertNotNull(dirs);
        assertTrue(dirs.contains("north"));
        assertTrue(dirs.contains("south"));
        assertFalse(dirs.contains("east"));
        assertFalse(dirs.contains("west"));
    }

    @Test
    @DisplayName("onCommand: bad parameters and error handling")
    void testOnCommandBadParameters() {
        UUID caller = UUID.randomUUID();
        TestTreeCmd root = new TestTreeCmd("root", "perm.root");

        DynamicParam param = new DynamicParam("perm.num", "param", Set.of("1", "2", "3"));
        root.addParameter("num", param);

        List<String> msgs = new ArrayList<>();

        // 1. Unknown parameter
        CompletableFuture<Boolean> resUnknown = root.onCommand(caller, p -> true, msgs::add, new String[]{"unknown=val"});
        assertFalse(resUnknown.join());
        assertTrue(root.badParams.stream().anyMatch(s -> s.contains("unknown=val")));

        // 2. Invalid value for parameter
        root.badParams.clear();
        CompletableFuture<Boolean> resInvalidVal = root.onCommand(caller, p -> true, msgs::add, new String[]{"num=999"});
        // Parameter validation fails for 999, so vals list is empty and parameter not added
        assertTrue(resInvalidVal.join());
        assertTrue(root.badParams.stream().anyMatch(s -> s.contains("num=999")));

        // 3. Permission denied on parameter
        root.badParams.clear();
        CompletableFuture<Boolean> resNoPerm = root.onCommand(caller, p -> !p.equals("perm.num"), msgs::add, new String[]{"num=1"});
        assertFalse(resNoPerm.join());
        assertTrue(root.badParams.stream().anyMatch(s -> s.contains("num=1")));

        // 4. Invalid command / unknown subcommand
        root.invalidCmds.clear();
        CompletableFuture<Boolean> resBadCmd = root.onCommand(caller, p -> true, msgs::add, new String[]{"nonexistent"});
        assertFalse(resBadCmd.join());
        assertTrue(root.invalidCmds.contains("nonexistent"));
    }

    @Test
    @DisplayName("onCommand: help subcommand handling")
    void testOnCommandHelp() {
        UUID caller = UUID.randomUUID();
        TestTreeCmd root = new TestTreeCmd("root", "perm.root");
        root.addParameter("param1", new DynamicParam("perm.p1", "desc1", Set.of("v1")));
        TestTreeCmd sub = new TestTreeCmd("sub1", "perm.s1");
        root.addSubCommand(sub);

        List<String> msgs = new ArrayList<>();
        CompletableFuture<Boolean> res = root.onCommand(caller, p -> true, msgs::add, new String[]{"help"});
        assertFalse(res.join()); // help returns false
        assertFalse(msgs.isEmpty());
        assertTrue(msgs.stream().anyMatch(m -> m.contains("Command: root")));
        assertTrue(msgs.stream().anyMatch(m -> m.contains("Subcommands:")));
        assertTrue(msgs.stream().anyMatch(m -> m.contains("sub1")));
        assertTrue(msgs.stream().anyMatch(m -> m.contains("Parameters:")));
        assertTrue(msgs.stream().anyMatch(m -> m.contains("param1")));
    }

    @Test
    @DisplayName("onCommand: subcommand execution order, executor queueing, and permission checks")
    void testOnCommandSubcommandExecution() {
        UUID caller = UUID.randomUUID();
        TestTreeCmd root = new TestTreeCmd("root", "perm.root");
        TestTreeCmd sub = new TestTreeCmd("sub", "perm.sub");
        root.addSubCommand(sub);

        List<String> msgs = new ArrayList<>();

        // 1. Permission denied on subcommand
        CompletableFuture<Boolean> deniedSub = root.onCommand(caller, p -> !p.equals("perm.sub"), msgs::add, new String[]{"sub"});
        assertFalse(deniedSub.join());
        assertTrue(msgs.contains("noPerm:perm.sub"));

        // Drain the pipeline so queued CommandExecutor doesn't leak into subsequent tests
        CommandsAPI.execute();

        // 2. Permission granted on subcommand
        msgs.clear();
        CompletableFuture<Boolean> okSub = root.onCommand(caller, p -> true, msgs::add, new String[]{"sub"});
        assertFalse(okSub.isDone(), "subcommand completion waits for parent CommandExecutor");

        // Drain CommandsAPI pipeline to run root's executor and trigger subcommand
        CommandsAPI.execute();
        assertTrue(okSub.join());
        assertTrue(sub.executed);
        assertTrue(msgs.contains("exec:root"));
        assertTrue(msgs.contains("exec:sub"));
    }

    @Test
    @DisplayName("onCommand: subparameters parsed from subsequent command-line args")
    void testOnCommandSubparameterArgChain() {
        UUID caller = UUID.randomUUID();
        TestTreeCmd root = new TestTreeCmd("root", "perm.root");

        DynamicParam modeParam = new DynamicParam("perm.mode", "mode", Set.of("custom"));
        DynamicParam optParam = new DynamicParam("perm.opt", "opt", Set.of("true", "false"));
        modeParam.subParamMap.put("custom", Map.of("option", optParam));

        root.addParameter("mode", modeParam);

        List<String> msgs = new ArrayList<>();
        CompletableFuture<Boolean> res = root.onCommand(caller, p -> true, msgs::add, new String[]{"mode=custom", "option=true"});
        assertTrue(res.join());
        assertNotNull(root.lastParamValues);
        assertEquals(List.of("custom"), root.lastParamValues.get("mode"));
        assertEquals(List.of("true"), root.lastParamValues.get("option"));
    }

    @Test
    @DisplayName("help method: multi-level hierarchy formatting")
    void testHelpMethodHierarchy() {
        TestTreeCmd greatGrandParent = new TestTreeCmd("root", "perm.r");
        TestTreeCmd grandParent = new TestTreeCmd("grand", "perm.g", greatGrandParent);
        TestTreeCmd parent = new TestTreeCmd("parent", "perm.p", grandParent);
        TestTreeCmd child = new TestTreeCmd("child", "perm.c", parent);

        List<String> helpLines = child.help(UUID.randomUUID(), p -> true);
        assertFalse(helpLines.isEmpty());
        String cmdHeader = helpLines.get(0);
        // Note: TreeCommand.help iterates parents loop `i > 0`, excluding index 0 (immediate parent)
        // parents list: [parent (index 0), grand (index 1), root (index 2)]
        // loop appends index 2 ("root"), index 1 ("grand"), then child.name() ("child")
        assertTrue(cmdHeader.contains("root grand child"), "Expected command chain in help: " + cmdHeader);
    }

    @Test
    @DisplayName("onTabComplete and onCommand: edge cases and defensive checks")
    void testTabCompleteEdgeCasesAndDefensiveChecks() {
        UUID caller = UUID.randomUUID();
        TestTreeCmd root = new TestTreeCmd("root", "perm.root");
        DynamicParam param = new DynamicParam("perm.p", "param", Set.of("val1", "val2"));
        DynamicParam subParam = new DynamicParam(null, "subparam", Set.of("s1", "s2"));
        param.subParamMap.put("val1", Map.of("sub", subParam));
        root.addParameter("p", param);

        TestTreeCmd sub = new TestTreeCmd("sub", null);
        root.addSubCommand(sub);

        // 1. args.length == 0
        List<String> emptyArgs = root.onTabComplete(caller, p -> true, new String[0]);
        assertFalse(emptyArgs.isEmpty());
        assertTrue(emptyArgs.contains("help"));

        // 2. non-last value that does not match subcommand: args=["unknown", ""]
        List<String> nonLastUnknown = root.onTabComplete(caller, p -> true, new String[]{"unknown", ""});
        assertTrue(nonLastUnknown.contains("p="));
        assertTrue(nonLastUnknown.contains("sub"));

        // 3. tempParameters containsKey in last position with comma: args=["p=val1", "sub=s1,"]
        List<String> tempComma = root.onTabComplete(caller, p -> true, new String[]{"p=val1", "sub=s1,"});
        assertTrue(tempComma.contains("sub=s1,s2"));

        // 4. default msgInvalidCommand on TreeCommand interface
        TreeCommand minimal = new TreeCommand() {
            private final Map<String, CommandParameter> p = new HashMap<>();
            private final Map<String, CommandsAPICommand> c = new HashMap<>();
            @Override public Map<String, CommandParameter> getParameterLookup() { return p; }
            @Override public Map<String, CommandsAPICommand> getCommandLookup() { return c; }
            @Override public String name() { return "min"; }
            @Override public String permission() { return "perm"; }
            @Override public String description() { return "desc"; }
            @Override public CommandsAPICommand parent() { return null; }
            @Override public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {}
            @Override public long avgTime() { return 0L; }
            @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) { return true; }
        };
        minimal.msgInvalidCommand(caller, "foo");
        minimal.msgInvalidCommand(caller, "foo", s -> {});
        assertEquals(0L, minimal.avgTime());

        // 5. onCommand when custom HELP subcommand exists
        TestTreeCmd rootWithHelpSub = new TestTreeCmd("root", "perm.root");
        TestTreeCmd customHelpSub = new TestTreeCmd("HELP", "perm.help");
        rootWithHelpSub.addSubCommand(customHelpSub);
        CompletableFuture<Boolean> helpSubExec = rootWithHelpSub.onCommand(caller, p -> true, s -> {}, new String[]{"help"});
        CommandsAPI.execute();
        assertTrue(helpSubExec.join());
        assertTrue(customHelpSub.executed);

        // 6. onCommand endsWith "=" (bad parameter format)
        List<String> badParamMsgs = new ArrayList<>();
        CompletableFuture<Boolean> endsWithEquals = root.onCommand(caller, p -> true, badParamMsgs::add, new String[]{"p="});
        assertFalse(endsWithEquals.join());
        assertTrue(badParamMsgs.stream().anyMatch(s -> s.contains("badParam:p=")));

        // 7. onTabComplete in last position with delimiterIdx < 0 when caller lacks permission
        List<String> deniedParamTab = root.onTabComplete(caller, p -> false, new String[]{"p", ""});
        assertFalse(deniedParamTab.contains("p="));
        assertFalse(deniedParamTab.contains("SUB"));

        // 8. onTabComplete when parameterValues already contains the parameter: args=["p=val1", ""]
        List<String> alreadyUsedTab = root.onTabComplete(caller, p -> true, new String[]{"p=val1", ""});
        assertFalse(alreadyUsedTab.contains("p="));

        // 9. onTabComplete when delimiterIdx > 0 and parameter was already used: args=["p=val1", "p=val2"]
        List<String> duplicateParamValTab = root.onTabComplete(caller, p -> true, new String[]{"p=val1", "p=val2"});
        assertTrue(duplicateParamValTab.isEmpty());

        // 10. onTabComplete subparameter tempParameters already used: args=["p=val1", "sub=s1", "sub=s2"]
        List<String> duplicateSubParamValTab = root.onTabComplete(caller, p -> true, new String[]{"p=val1", "sub=s1", "sub=s2"});
        assertTrue(duplicateSubParamValTab.isEmpty());

        // 11. onTabComplete tempParameters with permission check gating
        DynamicParam restrictedSub = new DynamicParam("perm.restricted", "desc", Set.of("r1"));
        param.subParamMap.put("val2", Map.of("restricted", restrictedSub));
        List<String> restrictedSubTab = root.onTabComplete(caller, p -> !p.equals("perm.restricted"), new String[]{"p=val2", ""});
        assertFalse(restrictedSubTab.contains("restricted="));

        // 12. onTabComplete resilience when registry contains null key or null value
        TestTreeCmd nullRegistryCmd = new TestTreeCmd("nulls", null);
        nullRegistryCmd.getParameterLookup().put(null, null);
        nullRegistryCmd.getParameterLookup().put("valid", param);
        Map<String, CommandParameter> tempWithNulls = new HashMap<>();
        tempWithNulls.put(null, null);
        tempWithNulls.put("validTemp", param);
        List<String> nullResTab = nullRegistryCmd.onTabComplete(caller, p -> true, new String[]{""}, 0, tempWithNulls);
        assertTrue(nullResTab.contains("valid="), "Valid parameter should be suggested even with null keys in lookup");
        assertTrue(nullResTab.contains("validTemp="), "Valid temporary parameter should be suggested even with null keys in temp lookup");

        // 13. onTabComplete with sub-parameter matching from temporary parameters
        Map<String, CommandParameter> loopTemp = new HashMap<>();
        loopTemp.put("sub", subParam);
        List<String> loopTempRes = root.onTabComplete(caller, p -> true, new String[]{"sub=s1", "next="}, 0, loopTemp);
        assertNotNull(loopTempRes);

        // 14. onTabComplete last position with unknown parameter containing delimiter
        List<String> unknownParamWithEquals = root.onTabComplete(caller, p -> true, new String[]{"unknownKey="});
        assertNotNull(unknownParamWithEquals);

        // 14b. onTabComplete delimiter > 0 with parameter in tempParameters and multiple values
        Map<String, CommandParameter> loopTemp2 = new HashMap<>();
        loopTemp2.put("sub", subParam);
        List<String> subValsWithComma = root.onTabComplete(caller, p -> true, new String[]{"sub=s1,"}, 0, loopTemp2);
        assertNotNull(subValsWithComma);

        // 15. onTabComplete expandRegexToken safely handles null token input
        assertEquals(0, TreeCommand.expandRegexToken(null, param, caller).count());

        // 16. onTabComplete intermediate argument parameter already used does not re-suggest
        List<String> notLastAlreadyUsed = root.onTabComplete(caller, p -> true, new String[]{"p=val1", "unknown", ""});
        assertFalse(notLastAlreadyUsed.contains("p="));

        // 17. onTabComplete intermediate argument temporary parameter already used does not re-suggest
        Map<String, CommandParameter> tempAlreadyUsed = new HashMap<>();
        tempAlreadyUsed.put("sub", subParam);
        List<String> notLastTempAlreadyUsed = root.onTabComplete(caller, p -> true, new String[]{"sub=s1", "unknown", ""}, 0, tempAlreadyUsed);
        assertFalse(notLastTempAlreadyUsed.contains("sub="));

        // 18. onTabComplete only-value with denied permission on parameters
        DynamicParam secretParam = new DynamicParam("perm.secret", "secret", Set.of("s1"));
        TestTreeCmd rootWithSecret = new TestTreeCmd("rootSec", null);
        rootWithSecret.addParameter("secret", secretParam);
        List<String> secretTabDenied = rootWithSecret.onTabComplete(caller, "perm.other"::equals, new String[0]);
        assertFalse(secretTabDenied.contains("secret="));

        // 19. onCommand execution halted when parameter permission check fails
        CompletableFuture<Boolean> paramDeniedExec = rootWithSecret.onCommand(caller, "perm.other"::equals, s -> {}, new String[]{"secret=s1"});
        assertFalse(paramDeniedExec.join(), "Command execution should fail when caller lacks permission for parameter");

        // 20. onCommand execution with chained subparameters where subparameter permission check fails
        DynamicParam unprivSubParam = new DynamicParam("perm.restrictedSub", "unsub", Set.of("v3"));
        param.subParamMap.put("val1", Map.of("unsub", unprivSubParam));
        CompletableFuture<Boolean> subParamDeniedExec = root.onCommand(caller, p -> !"perm.restrictedSub".equals(p), s -> {}, new String[]{"p=val1", "unsub=v3"});
        assertNotNull(subParamDeniedExec);

        // 20b. onCommand execution with chained subparameters where inner token has no equals delimiter
        CompletableFuture<Boolean> subParamNoDelim = root.onCommand(caller, p -> true, s -> {}, new String[]{"p=val1", "noDelimiter"});
        assertNotNull(subParamNoDelim);

        // 20c. onCommand execution with chained subparameters matching and passing validation
        DynamicParam validSubParam = new DynamicParam("perm.validSub", "valsub", Set.of("v4"));
        param.subParamMap.put("val1", Map.of("valsub", validSubParam));
        CompletableFuture<Boolean> subParamValid = root.onCommand(caller, p -> true, s -> {}, new String[]{"p=val1", "valsub=v4"});
        assertTrue(subParamValid.join());

        // 21. onCommand subcommand continuation returning false is reported properly
        TestTreeCmd failingSub = new TestTreeCmd("failSub", "perm.ok") {
            @Override public boolean onCommand(UUID c, Map<String, List<String>> p, CommandsAPICommand n) { return false; }
            @Override public boolean onCommand(UUID c, Map<String, List<String>> p, CommandsAPICommand n, Consumer<String> m) { return false; }
        };
        root.addSubCommand(failingSub);
        CompletableFuture<Boolean> failingSubRes = root.onCommand(caller, p -> true, s -> {}, new String[]{"failSub"});
        CommandsAPI.execute();
        assertNotNull(failingSubRes);

        // 22. onTabComplete subparameter splitting when arg has no equals sign
        List<String> noEqualsInChain = root.onTabComplete(caller, p -> true, new String[]{"p", "next="});
        assertNotNull(noEqualsInChain);

        // 23. onTabComplete intermediate argument null checks on tempParameters
        Map<String, CommandParameter> tempNullKeyVal = new HashMap<>();
        tempNullKeyVal.put(null, null);
        List<String> tempNullRes = root.onTabComplete(caller, p -> true, new String[]{"unknown", ""}, 0, tempNullKeyVal);
        assertNotNull(tempNullRes);

        // 24. onTabComplete last position null checks on tempParameters
        List<String> lastTempNullRes = root.onTabComplete(caller, p -> true, new String[]{"p=val1", ""}, 0, tempNullKeyVal);
        assertNotNull(lastTempNullRes);

        // 25. onTabComplete only-value with tempParameters null checks and permission checks
        Map<String, CommandParameter> onlyValNullTemp = new HashMap<>();
        onlyValNullTemp.put(null, null);
        onlyValNullTemp.put("restricted", restrictedSub);
        List<String> onlyValNullRes = root.onTabComplete(caller, "perm.root"::equals, new String[0], 0, onlyValNullTemp);
        assertFalse(onlyValNullRes.contains("restricted="));

        // 26. onTabComplete last position with delimiter < 0 and tempParameters permission checks
        List<String> lastTempPermRes = root.onTabComplete(caller, "perm.root"::equals, new String[]{"p=val1", ""}, 0, onlyValNullTemp);
        assertFalse(lastTempPermRes.contains("restricted="));

        // 27. onTabComplete only-value with parameterLookup permission checks
        TestTreeCmd rootWithDenied = new TestTreeCmd("rootDenied", null);
        rootWithDenied.addParameter("denied", restrictedSub);
        List<String> onlyValParamDenied = rootWithDenied.onTabComplete(caller, "perm.none"::equals, new String[0]);
        assertFalse(onlyValParamDenied.contains("denied="));

        // 28. onTabComplete last position with delimiter < 0 and parameterLookup null entries
        TestTreeCmd rootWithNullKV = new TestTreeCmd("nullkv", null);
        rootWithNullKV.getParameterLookup().put(null, null);
        rootWithNullKV.getParameterLookup().put("p", param);
        List<String> lastNullKVTab = rootWithNullKV.onTabComplete(caller, p -> true, new String[]{"other=val", ""});
        assertTrue(lastNullKVTab.contains("p="));

        // 29. onTabComplete delimiter > 0 with parameter having subparameters
        DynamicParam paramWithSub = new DynamicParam("perm.p", "p", Set.of("v1"));
        paramWithSub.subParamMap.put("v1", Map.of("sub", subParam));
        TestTreeCmd rootSubParams = new TestTreeCmd("rootsub", null);
        rootSubParams.addParameter("p", paramWithSub);
        List<String> tabSubParams = rootSubParams.onTabComplete(caller, p -> true, new String[]{"p=v1,"});
        assertNotNull(tabSubParams);

        // 30. onTabComplete only-value with tempParameters having null permission vs valid permission
        Map<String, CommandParameter> mixedPermTemp = new HashMap<>();
        mixedPermTemp.put("open", new DynamicParam(null, "open", Set.of("o")));
        mixedPermTemp.put("secret", new DynamicParam("perm.secret", "secret", Set.of("s")));
        List<String> mixedPermRes = root.onTabComplete(caller, "perm.secret"::equals, new String[]{""}, 0, mixedPermTemp);
        assertTrue(mixedPermRes.contains("open="));
        assertTrue(mixedPermRes.contains("secret="));

        // 31. onTabComplete chained subcommands traversal in non-last position
        TestTreeCmd midSub = new TestTreeCmd("mid", null);
        TestTreeCmd leafSub = new TestTreeCmd("leaf", null);
        midSub.addSubCommand(leafSub);
        root.addSubCommand(midSub);
        List<String> chainedSubTab = root.onTabComplete(caller, p -> true, new String[]{"mid", "leaf"});
        assertNotNull(chainedSubTab);

        // 32. onTabComplete only-value with null permission on commands
        TestTreeCmd noPermRoot = new TestTreeCmd("noperm", null);
        TestTreeCmd childNullPerm = new TestTreeCmd("child", null);
        noPermRoot.addSubCommand(childNullPerm);
        List<String> onlyValNullPermTab = noPermRoot.onTabComplete(caller, p -> true, new String[]{""});
        assertTrue(onlyValNullPermTab.contains("child"));

        // 33. onTabComplete sub-parameters where subParams returns null
        DynamicParam paramNoSub = new DynamicParam("perm.p", "nosub", Set.of("v1"));
        TestTreeCmd rootNoSub = new TestTreeCmd("rootnosub", null);
        rootNoSub.addParameter("nosub", paramNoSub);
        List<String> whileSubNull = rootNoSub.onTabComplete(caller, p -> true, new String[]{"nosub=v1", "next="});
        assertNotNull(whileSubNull);

        // 34. onCommand permission denial message feedback for root command and subcommand
        TestTreeCmd permRoot = new TestTreeCmd("permroot", "perm.required");
        permRoot.onCommand(caller, p -> false, s -> {}, new String[0]);
        assertTrue(permRoot.noPerms.contains("perm.required"));

        TestTreeCmd permSub = new TestTreeCmd("permsub", "perm.subreq");
        permRoot.addSubCommand(permSub);
        permRoot.onCommand(caller, "perm.required"::equals, s -> {}, new String[]{"permsub"});
        assertTrue(permSub.noPerms.contains("perm.subreq"));

        // Subcommand with null permission executes cleanly
        TestTreeCmd nullPermSub = new TestTreeCmd("nullpermsub", null);
        permRoot.addSubCommand(nullPermSub);
        CompletableFuture<Boolean> nullPermSubRes = permRoot.onCommand(caller, "perm.required"::equals, s -> {}, new String[]{"nullpermsub"});
        CommandsAPI.execute();
        assertNotNull(nullPermSubRes);

        // Subcommand with non-null permission passing
        TestTreeCmd passingPermSub = new TestTreeCmd("passingsub", "perm.subpass");
        permRoot.addSubCommand(passingPermSub);
        CompletableFuture<Boolean> passingPermSubRes = permRoot.onCommand(caller, p -> true, s -> {}, new String[]{"passingsub"});
        CommandsAPI.execute();
        assertNotNull(passingPermSubRes);

        // Root with null permission executes cleanly
        TestTreeCmd nullPermRootCmd = new TestTreeCmd("nullpermroot", null);
        CompletableFuture<Boolean> nullPermRootRes = nullPermRootCmd.onCommand(caller, p -> false, s -> {}, new String[0]);
        assertTrue(nullPermRootRes.join());

        // Root with non-null permission passing
        TestTreeCmd passingPermRoot = new TestTreeCmd("passingroot", "perm.rootpass");
        CompletableFuture<Boolean> passingPermRootRes = passingPermRoot.onCommand(caller, "perm.rootpass"::equals, s -> {}, new String[0]);
        assertTrue(passingPermRootRes.join());

        // 35. onCommand parameter with null permission is allowed through
        TestTreeCmd nullPermParamRoot = new TestTreeCmd("nullpermparamroot", null);
        nullPermParamRoot.addParameter("open", new DynamicParam(null, "open", Set.of("v"), s -> true));
        CompletableFuture<Boolean> nullPermParamRes = nullPermParamRoot.onCommand(caller, p -> false, s -> {}, new String[]{"open=v"});
        assertTrue(nullPermParamRes.join());
      }
}
