package io.github.dailystruggle.rtp.api.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Surface-shape tests for the {@code rtp-api} menu types (ADR-035, ADR-044).
 * Pins defensive copies, null rejection, sealed-action exhaustiveness, and defaults.
 */
@DisplayName("ADR-035 / ADR-044 - rtp-api menu surface")
class MenuModelSurfaceTest {

    private static MenuFragment frag() {
        return new MenuFragment("text", "hover", new MenuAction.ChangePage(0));
    }

    // ---- MenuAction ----

    @Test
    @DisplayName("MenuAction is sealed to exactly the twenty-six declared variants")
    void menuActionSealedShape() {
        Class<?>[] permitted = MenuAction.class.getPermittedSubclasses();
        assertEquals(26, permitted.length);
        List<String> names = Arrays.stream(permitted).map(Class::getSimpleName).sorted().toList();
        assertEquals(List.of(
                "ApplyStagedConfig",
                "ChangePage",
                "DiscardStagedConfig",
                "MultiConfigMutate",
                "OpenAdminPanel",
                "OpenConfigFile",
                "OpenConfigKey",
                "OpenConfigSearchPrompt",
                "OpenConfigSearchResults",
                "OpenConfigSelector",
                "OpenConfigSubParamPage",
                "OpenExternalUrl",
                "OpenFrontPage",
                "OpenInfo",
                "OpenMap",
                "OpenMenu",
                "OpenMultiConfigEntry",
                "OpenMultiConfigSelector",
                "OpenParamPicker",
                "OpenVisualizations",
                "PromptAnvilInput",
                "RunRtpCommand",
                "StageConfigValue",
                "SuggestInput",
                "SwitchInfoToText",
                "UnstageConfigValue"), names);
    }

    @Test
    @DisplayName("OpenMenu defensively copies path in and out; equality is by contents; rejects null elements")
    void openMenuDefensiveCopy() {
        String[] in = {"config", "performance"};
        MenuAction.OpenMenu a = new MenuAction.OpenMenu(in);
        in[0] = "MUTATED";
        assertArrayEquals(new String[]{"config", "performance"}, a.path());
        String[] out = a.path();
        out[0] = "ALSO_MUTATED";
        assertArrayEquals(new String[]{"config", "performance"}, a.path());

        MenuAction.OpenMenu b = new MenuAction.OpenMenu(new String[]{"config", "performance"});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        assertThrows(NullPointerException.class, () -> new MenuAction.OpenMenu(null));
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenMenu(new String[]{"a", null}));

        // Empty path is the root-menu marker, must be accepted.
        MenuAction.OpenMenu root = new MenuAction.OpenMenu(new String[0]);
        assertEquals(0, root.path().length);
    }

    @Test
    @DisplayName("RunRtpCommand defensively copies args in and out; equality is by contents")
    void runRtpCommandDefensiveCopy() {
        String[] in = {"config", "performance"};
        MenuAction.RunRtpCommand a = new MenuAction.RunRtpCommand(in);
        in[0] = "MUTATED";
        assertArrayEquals(new String[]{"config", "performance"}, a.args());
        String[] out = a.args();
        out[0] = "ALSO_MUTATED";
        assertArrayEquals(new String[]{"config", "performance"}, a.args());

        MenuAction.RunRtpCommand b = new MenuAction.RunRtpCommand(new String[]{"config", "performance"});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("RunRtpCommand rejects null array and null elements")
    void runRtpCommandNullRejection() {
        assertThrows(NullPointerException.class, () -> new MenuAction.RunRtpCommand(null));
        assertThrows(NullPointerException.class, () -> new MenuAction.RunRtpCommand(new String[]{"a", null}));
    }

    @Test
    @DisplayName("ChangePage rejects negative index")
    void changePageNegativeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.ChangePage(-1));
        new MenuAction.ChangePage(0);
        new MenuAction.ChangePage(42);
    }

    @Test
    @DisplayName("SuggestInput and OpenExternalUrl reject null required args")
    void leafActionNullRejection() {
        assertThrows(NullPointerException.class, () -> new MenuAction.SuggestInput(null));
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenExternalUrl(null));
        new MenuAction.SuggestInput("/rtp config performance ASYNC:");
        new MenuAction.OpenExternalUrl(URI.create("https://example.invalid/help"));
    }

    @Test
    @DisplayName("PromptAnvilInput defensively copies parentPath; equality by contents; rejects nulls + empty name")
    void promptAnvilInputDefensiveCopy() {
        String[] in = {"regions"};
        MenuAction.PromptAnvilInput a = new MenuAction.PromptAnvilInput(in, "add", "");
        in[0] = "MUTATED";
        assertArrayEquals(new String[]{"regions"}, a.parentPath());
        String[] out = a.parentPath();
        out[0] = "ALSO_MUTATED";
        assertArrayEquals(new String[]{"regions"}, a.parentPath());
        assertEquals("add", a.paramName());
        assertEquals("", a.prefill());

        MenuAction.PromptAnvilInput b = new MenuAction.PromptAnvilInput(
                new String[]{"regions"}, "add", "");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        MenuAction.PromptAnvilInput different = new MenuAction.PromptAnvilInput(
                new String[]{"regions"}, "add", "seed");
        assertFalse(a.equals(different));

        assertThrows(NullPointerException.class,
                () -> new MenuAction.PromptAnvilInput(null, "add", ""));
        assertThrows(NullPointerException.class,
                () -> new MenuAction.PromptAnvilInput(new String[]{null}, "add", ""));
        assertThrows(NullPointerException.class,
                () -> new MenuAction.PromptAnvilInput(new String[0], null, ""));
        assertThrows(NullPointerException.class,
                () -> new MenuAction.PromptAnvilInput(new String[0], "add", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MenuAction.PromptAnvilInput(new String[0], "", ""));
    }

    @Test
    @DisplayName("Pattern-matching switch over MenuAction compiles exhaustively without default")
    void menuActionSwitchExhaustive() {
        // Compilation alone is the assertion: if a variant is added without updating callers
        // (or this test) the compiler refuses. The arms also exercise each accessor.
        MenuAction action = new MenuAction.RunRtpCommand(new String[]{"config"});
        String tag = switch (action) {
            case MenuAction.RunRtpCommand r -> "run:" + r.args().length;
            case MenuAction.OpenMenu o -> "open:" + o.path().length;
            case MenuAction.OpenParamPicker p -> "pick:" + p.parentPath().length + ":" + p.paramName();
            case MenuAction.ChangePage c -> "page:" + c.pageIndex();
            case MenuAction.SuggestInput s -> "suggest:" + s.prefix();
            case MenuAction.PromptAnvilInput pa -> "anvil:" + pa.paramName();
            case MenuAction.OpenExternalUrl u -> "url:" + u.uri();
            case MenuAction.OpenConfigSelector cs -> "cfgsel";
            case MenuAction.OpenConfigFile cf -> "cfgfile:" + cf.fileName();
            case MenuAction.OpenConfigKey ck -> "cfgkey:" + ck.fileName() + ":" + ck.paramName();
            case MenuAction.OpenConfigSubParamPage sp -> "cfgsub:" + sp.fileName() + ":" + sp.paramName() + ":" + sp.typeName();
            case MenuAction.OpenConfigSearchPrompt sp -> "cfgsearchprompt";
            case MenuAction.OpenConfigSearchResults sr -> "cfgsearch:" + sr.query() + ":" + sr.page();
            case MenuAction.OpenAdminPanel ap -> "admin";
            case MenuAction.OpenVisualizations ov -> "viz";
            case MenuAction.OpenFrontPage fp -> "front";
            case MenuAction.OpenInfo oi -> "info:" + oi.scope().kind() + ":" + oi.scope().name();
            case MenuAction.SwitchInfoToText sit -> "info-text:" + sit.scope().kind() + ":" + sit.scope().name();
            case MenuAction.StageConfigValue sv -> "stage:" + sv.fileName() + ":" + sv.paramName() + "=" + sv.value();
            case MenuAction.UnstageConfigValue uv -> "unstage:" + uv.fileName() + ":" + uv.paramName();
            case MenuAction.ApplyStagedConfig ac -> "apply:" + ac.fileName();
            case MenuAction.DiscardStagedConfig dc -> "discard:" + dc.fileName();
            case MenuAction.OpenMap om -> "map:" + om.kind() + ":" + om.regionName();
            case MenuAction.OpenMultiConfigSelector mcs -> "mcsel:" + mcs.parserKind();
            case MenuAction.OpenMultiConfigEntry mce -> "mcentry:" + mce.parserKind() + ":" + mce.entryName();
            case MenuAction.MultiConfigMutate mcm -> "mcmut:" + mcm.parserKind() + ":" + mcm.entryName() + ":" + mcm.op();
        };
        assertEquals("run:1", tag);
    }

    @Test
    @DisplayName("PromptAnvilInput.mode defaults to RUN via 3-arg ctor; STAGE distinguishes equality")
    void promptAnvilInputModeBackCompat() {
        MenuAction.PromptAnvilInput legacy = new MenuAction.PromptAnvilInput(
                new String[]{"config"}, "key", "");
        assertEquals(MenuAction.Mode.RUN, legacy.mode());
        MenuAction.PromptAnvilInput stage = new MenuAction.PromptAnvilInput(
                new String[]{"config"}, "key", "", MenuAction.Mode.STAGE);
        assertEquals(MenuAction.Mode.STAGE, stage.mode());
        assertFalse(legacy.equals(stage));
        assertThrows(NullPointerException.class,
                () -> new MenuAction.PromptAnvilInput(new String[]{"config"}, "key", "", null));
    }

    @Test
    @DisplayName("InfoScopeToken / OpenInfo / SwitchInfoToText reject nulls and enforce GLOBAL has empty name")
    void infoScopeTokenValidation() {
        MenuAction.InfoScopeToken global = MenuAction.InfoScopeToken.global();
        assertEquals(MenuAction.InfoScopeToken.Kind.GLOBAL, global.kind());
        assertEquals("", global.name());

        MenuAction.InfoScopeToken world = MenuAction.InfoScopeToken.world("the_end");
        assertEquals(MenuAction.InfoScopeToken.Kind.WORLD, world.kind());
        assertEquals("the_end", world.name());

        MenuAction.InfoScopeToken region = MenuAction.InfoScopeToken.region("default");
        assertEquals(MenuAction.InfoScopeToken.Kind.REGION, region.kind());
        assertEquals("default", region.name());

        assertThrows(NullPointerException.class,
                () -> new MenuAction.InfoScopeToken(null, ""));
        assertThrows(NullPointerException.class,
                () -> new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.GLOBAL, null));
        // GLOBAL must have empty name
        assertThrows(IllegalArgumentException.class,
                () -> new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.GLOBAL, "bogus"));
        // Non-GLOBAL must have non-empty name
        assertThrows(IllegalArgumentException.class,
                () -> new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.WORLD, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.REGION, ""));

        // OpenInfo / SwitchInfoToText reject null scope.
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenInfo(null));
        assertThrows(NullPointerException.class, () -> new MenuAction.SwitchInfoToText(null));

        MenuAction.OpenInfo a = new MenuAction.OpenInfo(global);
        MenuAction.OpenInfo b = new MenuAction.OpenInfo(MenuAction.InfoScopeToken.global());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(new MenuAction.OpenInfo(world)));
    }

    @Test
    @DisplayName("Staging-cart records validate fileName/paramName/value and reject empties")
    void stagingCartRecordValidation() {
        MenuAction.StageConfigValue stage = new MenuAction.StageConfigValue(
                "performance", "teleportDelay", "5");
        assertEquals("performance", stage.fileName());
        assertEquals("teleportDelay", stage.paramName());
        assertEquals("5", stage.value());
        assertThrows(NullPointerException.class,
                () -> new MenuAction.StageConfigValue(null, "k", "v"));
        assertThrows(NullPointerException.class,
                () -> new MenuAction.StageConfigValue("f", null, "v"));
        assertThrows(NullPointerException.class,
                () -> new MenuAction.StageConfigValue("f", "k", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MenuAction.StageConfigValue("", "k", "v"));
        assertThrows(IllegalArgumentException.class,
                () -> new MenuAction.StageConfigValue("f", "", "v"));
        // value is allowed to be empty (it's a legitimate "set to blank" gesture).
        MenuAction.StageConfigValue blank = new MenuAction.StageConfigValue("f", "k", "");
        assertEquals("", blank.value());

        MenuAction.UnstageConfigValue unstage = new MenuAction.UnstageConfigValue("safety", "checkRadius");
        assertEquals("safety", unstage.fileName());
        assertEquals("checkRadius", unstage.paramName());
        assertThrows(IllegalArgumentException.class,
                () -> new MenuAction.UnstageConfigValue("", "k"));
        assertThrows(IllegalArgumentException.class,
                () -> new MenuAction.UnstageConfigValue("f", ""));

        MenuAction.ApplyStagedConfig apply = new MenuAction.ApplyStagedConfig("performance");
        assertEquals("performance", apply.fileName());
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.ApplyStagedConfig(""));

        MenuAction.DiscardStagedConfig discard = new MenuAction.DiscardStagedConfig("performance");
        assertEquals("performance", discard.fileName());
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.DiscardStagedConfig(""));
    }

    @Test
    @DisplayName("OpenParamPicker defensively copies parentPath; equality by contents; rejects nulls + empty name")
    void openParamPickerDefensiveCopy() {
        String[] in = {"config", "performance"};
        MenuAction.OpenParamPicker a = new MenuAction.OpenParamPicker(in, "ASYNC");
        in[0] = "MUTATED";
        assertArrayEquals(new String[]{"config", "performance"}, a.parentPath());
        String[] out = a.parentPath();
        out[1] = "ALSO_MUTATED";
        assertArrayEquals(new String[]{"config", "performance"}, a.parentPath());
        assertEquals("ASYNC", a.paramName());

        MenuAction.OpenParamPicker b = new MenuAction.OpenParamPicker(
                new String[]{"config", "performance"}, "ASYNC");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        // Distinct paramName / parentPath produce distinct values.
        MenuAction.OpenParamPicker different = new MenuAction.OpenParamPicker(
                new String[]{"config", "performance"}, "OTHER");
        assertFalse(a.equals(different));

        assertThrows(NullPointerException.class,
                () -> new MenuAction.OpenParamPicker(null, "ASYNC"));
        assertThrows(NullPointerException.class,
                () -> new MenuAction.OpenParamPicker(new String[]{"a", null}, "ASYNC"));
        assertThrows(NullPointerException.class,
                () -> new MenuAction.OpenParamPicker(new String[]{"a"}, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MenuAction.OpenParamPicker(new String[]{"a"}, ""));

        // Empty parentPath (param picker at the /rtp root level) is accepted.
        MenuAction.OpenParamPicker rootLevel = new MenuAction.OpenParamPicker(new String[0], "X");
        assertEquals(0, rootLevel.parentPath().length);
    }

    // ---- MenuFragment ----

    @Test
    @DisplayName("MenuFragment allows null hover and null action; rejects null text")
    void menuFragmentNullability() {
        MenuFragment f = new MenuFragment("hello", null, null);
        assertEquals("hello", f.text());
        assertNull(f.hover());
        assertNull(f.action());
        assertThrows(NullPointerException.class, () -> new MenuFragment(null, "h", null));
        MenuFragment plain = MenuFragment.plain("x");
        assertNull(plain.hover());
        assertNull(plain.action());
    }

    // ---- MenuLine / MenuPage / MenuModel ----

    @Test
    @DisplayName("MenuLine defensively copies the fragment list")
    void menuLineDefensiveCopy() {
        List<MenuFragment> mutable = new ArrayList<>();
        mutable.add(frag());
        MenuLine line = new MenuLine(mutable);
        mutable.add(frag());
        assertEquals(1, line.fragments().size());
        assertThrows(UnsupportedOperationException.class, () -> line.fragments().add(frag()));
    }

    @Test
    @DisplayName("MenuLine and MenuPage reject null entries")
    void menuLineNullEntryRejected() {
        List<MenuFragment> withNull = new ArrayList<>();
        withNull.add(frag());
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new MenuLine(withNull));

        List<MenuLine> linesWithNull = new ArrayList<>();
        linesWithNull.add(MenuLine.of(frag()));
        linesWithNull.add(null);
        assertThrows(NullPointerException.class, () -> new MenuPage(linesWithNull));
    }

    @Test
    @DisplayName("MenuModel requires at least one page and defensively copies")
    void menuModelShape() {
        assertThrows(IllegalArgumentException.class, () -> new MenuModel("t", List.of()));
        assertThrows(NullPointerException.class, () -> new MenuModel(null, List.of(new MenuPage(List.of()))));
        assertThrows(NullPointerException.class, () -> new MenuModel("t", null));

        List<MenuPage> mutable = new ArrayList<>();
        mutable.add(new MenuPage(List.of(MenuLine.of(frag()))));
        MenuModel model = new MenuModel("title", mutable);
        mutable.add(new MenuPage(List.of(MenuLine.of(frag()))));
        assertEquals(1, model.pages().size());
        assertNotSame(mutable, model.pages());
        assertThrows(UnsupportedOperationException.class,
                () -> model.pages().add(new MenuPage(List.of(MenuLine.of(frag())))));
    }

    // ---- YamlCommentLookup / MenuConsumerProfile ----

    @Test
    @DisplayName("YamlCommentLookup.EMPTY returns empty for any key")
    void yamlCommentLookupEmpty() {
        Optional<String> result = YamlCommentLookup.EMPTY.commentFor("config", "queue.threadCount");
        assertTrue(result.isEmpty());
        assertFalse(YamlCommentLookup.EMPTY.commentFor("", "").isPresent());
    }

    @Test
    @DisplayName("Default MenuConsumerProfile builds /<path…> <param>= prefix and uses empty comment lookup")
    void defaultProfilePrefix() {
        MenuConsumerProfile p = MenuConsumerProfile.defaultProfile();
        Deque<String> path = new ArrayDeque<>();
        path.add("rtp");
        path.add("config");
        path.add("performance");
        assertEquals("/rtp config performance ASYNC=", p.suggestPrefix(path, "ASYNC"));

        // Empty path produces "/<param>="
        assertEquals("/ASYNC=", p.suggestPrefix(new ArrayDeque<>(), "ASYNC"));

        assertEquals(YamlCommentLookup.EMPTY, p.commentLookup());
        assertThrows(NullPointerException.class, () -> p.suggestPrefix(null, "X"));
        assertThrows(NullPointerException.class, () -> p.suggestPrefix(new ArrayDeque<>(), null));
    }

    @Test
    @DisplayName("MenuOpenRequest validation and factory")
    void menuOpenRequest() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        MenuOpenRequest req = new MenuOpenRequest(uuid, 3);
        assertEquals(uuid, req.viewer());
        assertEquals(3, req.pageIndex());

        MenuOpenRequest first = MenuOpenRequest.firstPage(uuid);
        assertEquals(uuid, first.viewer());
        assertEquals(0, first.pageIndex());

        assertThrows(NullPointerException.class, () -> new MenuOpenRequest(null, 0));
        assertThrows(NullPointerException.class, () -> MenuOpenRequest.firstPage(null));
        assertThrows(IllegalArgumentException.class, () -> new MenuOpenRequest(uuid, -1));
    }

    @Test
    @DisplayName("MenuAction additional variants verification")
    void menuActionVariantsVerification() {
        // OpenConfigSelector
        MenuAction.OpenConfigSelector selRoot = new MenuAction.OpenConfigSelector();
        assertEquals("", selRoot.subDir());
        MenuAction.OpenConfigSelector selNull = new MenuAction.OpenConfigSelector(null);
        assertEquals("", selNull.subDir());
        MenuAction.OpenConfigSelector selClean = new MenuAction.OpenConfigSelector(" /foo\\bar/ ");
        assertEquals("foo/bar", selClean.subDir());

        // OpenConfigFile
        MenuAction.OpenConfigFile cfgFile = new MenuAction.OpenConfigFile("config.yml");
        assertEquals("config.yml", cfgFile.fileName());
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenConfigFile(null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenConfigFile(""));

        // OpenConfigKey
        MenuAction.OpenConfigKey cfgKey = new MenuAction.OpenConfigKey("config.yml", "key");
        assertEquals("config.yml", cfgKey.fileName());
        assertEquals("key", cfgKey.paramName());
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenConfigKey(null, "key"));
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenConfigKey("file", null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenConfigKey("", "key"));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenConfigKey("file", ""));

        // OpenConfigSearchPrompt
        assertNotNull(new MenuAction.OpenConfigSearchPrompt());

        // OpenConfigSearchResults
        MenuAction.OpenConfigSearchResults search = new MenuAction.OpenConfigSearchResults("query", 2);
        assertEquals("query", search.query());
        assertEquals(2, search.page());
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenConfigSearchResults(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenConfigSearchResults("q", -1));

        // OpenConfigSubParamPage
        MenuAction.OpenConfigSubParamPage subParam = new MenuAction.OpenConfigSubParamPage("file", "param", "type");
        assertEquals("file", subParam.fileName());
        assertEquals("param", subParam.paramName());
        assertEquals("type", subParam.typeName());
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenConfigSubParamPage(null, "p", "t"));
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenConfigSubParamPage("f", null, "t"));
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenConfigSubParamPage("f", "p", null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenConfigSubParamPage("", "p", "t"));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenConfigSubParamPage("f", "", "t"));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenConfigSubParamPage("f", "p", ""));

        // OpenAdminPanel, OpenVisualizations, OpenFrontPage
        assertNotNull(new MenuAction.OpenAdminPanel());
        assertNotNull(new MenuAction.OpenVisualizations());
        assertNotNull(new MenuAction.OpenFrontPage());

        // InfoScopeToken
        MenuAction.InfoScopeToken globalScope = MenuAction.InfoScopeToken.global();
        assertEquals(MenuAction.InfoScopeToken.Kind.GLOBAL, globalScope.kind());
        assertEquals("", globalScope.name());

        MenuAction.InfoScopeToken worldScope = MenuAction.InfoScopeToken.world("world_nether");
        assertEquals(MenuAction.InfoScopeToken.Kind.WORLD, worldScope.kind());
        assertEquals("world_nether", worldScope.name());

        MenuAction.InfoScopeToken regionScope = MenuAction.InfoScopeToken.region("spawn");
        assertEquals(MenuAction.InfoScopeToken.Kind.REGION, regionScope.kind());
        assertEquals("spawn", regionScope.name());

        assertThrows(NullPointerException.class, () -> new MenuAction.InfoScopeToken(null, ""));
        assertThrows(NullPointerException.class, () -> new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.GLOBAL, null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.GLOBAL, "notEmpty"));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.WORLD, ""));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.REGION, ""));

        // OpenInfo, SwitchInfoToText
        MenuAction.OpenInfo openInfo = new MenuAction.OpenInfo(globalScope);
        assertEquals(globalScope, openInfo.scope());
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenInfo(null));

        MenuAction.SwitchInfoToText switchInfo = new MenuAction.SwitchInfoToText(worldScope);
        assertEquals(worldScope, switchInfo.scope());
        assertThrows(NullPointerException.class, () -> new MenuAction.SwitchInfoToText(null));

        // StageConfigValue
        MenuAction.StageConfigValue stage = new MenuAction.StageConfigValue("file", "param", "val");
        assertEquals("file", stage.fileName());
        assertEquals("param", stage.paramName());
        assertEquals("val", stage.value());
        assertThrows(NullPointerException.class, () -> new MenuAction.StageConfigValue(null, "p", "v"));
        assertThrows(NullPointerException.class, () -> new MenuAction.StageConfigValue("f", null, "v"));
        assertThrows(NullPointerException.class, () -> new MenuAction.StageConfigValue("f", "p", null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.StageConfigValue("", "p", "v"));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.StageConfigValue("f", "", "v"));

        // UnstageConfigValue
        MenuAction.UnstageConfigValue unstage = new MenuAction.UnstageConfigValue("file", "param");
        assertEquals("file", unstage.fileName());
        assertEquals("param", unstage.paramName());
        assertThrows(NullPointerException.class, () -> new MenuAction.UnstageConfigValue(null, "p"));
        assertThrows(NullPointerException.class, () -> new MenuAction.UnstageConfigValue("f", null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.UnstageConfigValue("", "p"));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.UnstageConfigValue("f", ""));

        // ApplyStagedConfig, DiscardStagedConfig
        MenuAction.ApplyStagedConfig apply = new MenuAction.ApplyStagedConfig("file");
        assertEquals("file", apply.fileName());
        assertThrows(NullPointerException.class, () -> new MenuAction.ApplyStagedConfig(null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.ApplyStagedConfig(""));

        MenuAction.DiscardStagedConfig discard = new MenuAction.DiscardStagedConfig("file");
        assertEquals("file", discard.fileName());
        assertThrows(NullPointerException.class, () -> new MenuAction.DiscardStagedConfig(null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.DiscardStagedConfig(""));

        // OpenMap
        MenuAction.OpenMap map = new MenuAction.OpenMap(io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BIOMES, "region1");
        assertEquals(io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BIOMES, map.kind());
        assertEquals("region1", map.regionName());
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenMap(null, "r"));
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenMap(io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BIOMES, null));

        // OpenMultiConfigSelector
        MenuAction.OpenMultiConfigSelector multiSel = new MenuAction.OpenMultiConfigSelector("REGIONS");
        assertEquals("regions", multiSel.parserKind());
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenMultiConfigSelector(null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenMultiConfigSelector(""));

        // OpenMultiConfigEntry
        MenuAction.OpenMultiConfigEntry multiEntry = new MenuAction.OpenMultiConfigEntry("REGIONS", " entry_1-a.b ");
        assertEquals("regions", multiEntry.parserKind());
        assertEquals("entry_1-a.b", multiEntry.entryName());
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenMultiConfigEntry(null, "e"));
        assertThrows(NullPointerException.class, () -> new MenuAction.OpenMultiConfigEntry("r", null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenMultiConfigEntry("", "e"));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenMultiConfigEntry("r", ""));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.OpenMultiConfigEntry("r", "invalid/entry"));

        // MultiConfigMutate
        MenuAction.MultiConfigMutate mutate = new MenuAction.MultiConfigMutate("REGIONS", " entry_1 ", MenuAction.MultiConfigMutate.Op.ADD);
        assertEquals("regions", mutate.parserKind());
        assertEquals("entry_1", mutate.entryName());
        assertEquals(MenuAction.MultiConfigMutate.Op.ADD, mutate.op());
        assertThrows(NullPointerException.class, () -> new MenuAction.MultiConfigMutate(null, "e", MenuAction.MultiConfigMutate.Op.ADD));
        assertThrows(NullPointerException.class, () -> new MenuAction.MultiConfigMutate("r", null, MenuAction.MultiConfigMutate.Op.ADD));
        assertThrows(NullPointerException.class, () -> new MenuAction.MultiConfigMutate("r", "e", null));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.MultiConfigMutate("", "e", MenuAction.MultiConfigMutate.Op.ADD));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.MultiConfigMutate("r", "", MenuAction.MultiConfigMutate.Op.ADD));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction.MultiConfigMutate("r", "inv alid", MenuAction.MultiConfigMutate.Op.ADD));

        // MenuRendererProvider default method
        MenuRendererProvider provider = new MenuRendererProvider() {
            @Override
            public String id() { return "test"; }
            @Override
            public MenuRenderer create() { return null; }
        };
        assertNull(provider.platformFamily());

        // BookSpecBuilder tests
        UUID testPlayer = UUID.randomUUID();
        MenuModel model = new MenuModel("Test &aTitle", List.of(
                new MenuPage(List.of(
                        new MenuLine(List.of(
                                new MenuFragment("Click &bMe", "Hover &cText", new MenuAction.RunRtpCommand(new String[]{"rtp", "world"}))
                        ))
                ))
        ));

        // With explicit formatter and actionMapper
        BookSpec spec = BookSpecBuilder.buildSpec(
                testPlayer,
                model,
                (uuid, text) -> text.replace('&', '§'),
                action -> (action instanceof MenuAction.RunRtpCommand cmd) ? "/" + String.join(" ", cmd.args()) : null
        );
        assertEquals("Test §aTitle", spec.title());
        assertEquals(1, spec.pages().size());
        BookSpec.Page p0 = spec.pages().get(0);
        assertEquals(1, p0.lines().size());
        BookSpec.Line l0 = p0.lines().get(0);
        assertEquals(1, l0.fragments().size());
        BookSpec.Fragment f0 = l0.fragments().get(0);
        assertEquals("Click §bMe", f0.text());
        assertEquals("Hover §cText", f0.hover());
        assertEquals("/rtp world", f0.runCommand());

        // Null formatter, null actionMapper, empty strings, null model check
        assertThrows(NullPointerException.class, () -> BookSpecBuilder.buildSpec(testPlayer, null, null, null));
        MenuModel emptyModel = new MenuModel("", List.of(
                new MenuPage(List.of(
                        new MenuLine(List.of(
                                new MenuFragment("", "", null)
                        ))
                ))
        ));
        BookSpec emptySpec = BookSpecBuilder.buildSpec(null, emptyModel, null, null);
        assertEquals("", emptySpec.title());
        assertEquals(1, emptySpec.pages().size());
        BookSpec.Fragment emptyFrag = emptySpec.pages().get(0).lines().get(0).fragments().get(0);
        assertEquals("", emptyFrag.text());
        assertEquals("", emptyFrag.hover());
        assertNull(emptyFrag.runCommand());
    }
}
