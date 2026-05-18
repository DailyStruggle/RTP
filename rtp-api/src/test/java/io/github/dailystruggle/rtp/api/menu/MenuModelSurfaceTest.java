package io.github.dailystruggle.rtp.api.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Surface-shape tests for the {@code rtp-api} menu types.
 *
 * <p>Stage 1 of the generalized-menu rollout (ADR-035 amended + ADR-044).
 * These tests pin the public contract of the platform-neutral model: defensive
 * copies, null-arg rejection, sealed-action exhaustiveness, and the trivial
 * profile/lookup defaults. No behavior tests — those land alongside the
 * {@code rtp-core} implementations in Stage 2.
 */
@DisplayName("ADR-035 / ADR-044 § rtp-api menu surface")
class MenuModelSurfaceTest {

    private static MenuFragment frag() {
        return new MenuFragment("text", "hover", new MenuAction.ChangePage(0));
    }

    // ---- MenuAction ----

    @Test
    @DisplayName("MenuAction is sealed to exactly the ten declared variants")
    void menuActionSealedShape() {
        Class<?>[] permitted = MenuAction.class.getPermittedSubclasses();
        assertEquals(10, permitted.length);
        List<String> names = Arrays.stream(permitted).map(Class::getSimpleName).sorted().toList();
        assertEquals(List.of(
                "ChangePage",
                "OpenConfigFile",
                "OpenConfigKey",
                "OpenConfigSelector",
                "OpenExternalUrl",
                "OpenMenu",
                "OpenParamPicker",
                "PromptAnvilInput",
                "RunRtpCommand",
                "SuggestInput"), names);
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
        };
        assertEquals("run:1", tag);
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
    @DisplayName("Default MenuConsumerProfile builds /<path…> <param>: prefix and uses empty comment lookup")
    void defaultProfilePrefix() {
        MenuConsumerProfile p = MenuConsumerProfile.defaultProfile();
        Deque<String> path = new ArrayDeque<>();
        path.add("rtp");
        path.add("config");
        path.add("performance");
        assertEquals("/rtp config performance ASYNC:", p.suggestPrefix(path, "ASYNC"));

        // Empty path produces "/<param>:"
        assertEquals("/ASYNC:", p.suggestPrefix(new ArrayDeque<>(), "ASYNC"));

        assertEquals(YamlCommentLookup.EMPTY, p.commentLookup());
        assertThrows(NullPointerException.class, () -> p.suggestPrefix(null, "X"));
        assertThrows(NullPointerException.class, () -> p.suggestPrefix(new ArrayDeque<>(), null));
    }
}
