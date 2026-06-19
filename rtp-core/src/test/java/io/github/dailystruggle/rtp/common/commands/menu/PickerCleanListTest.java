package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The region / world parameter pickers are closed destination lists, so the
 * free-form "type a custom value..." anvil row is omitted there (the page is
 * just a clean, colorized list), while every other parameter keeps the
 * fallback row. Each value row carries a {@code &x} color prefix.
 */
class PickerCleanListTest {

    @Test
    void regionAndWorldPickersOmitTypeValueRow_otherParamsKeepIt() {
        TestRoot root = new TestRoot();
        root.getParameterLookup().put("region", enumParam("alpha", "beta"));
        root.getParameterLookup().put("world", enumParam("nether", "the_end"));
        root.getParameterLookup().put("biome", enumParam("plains", "desert"));

        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();
        MenuConsumerProfile profile = MenuConsumerProfile.defaultProfile();

        assertFalse(hasTypeValueRow(builder.buildParamPicker(
                root, UUID.randomUUID(), p -> true, profile, List.of(), "region")),
                "region picker must not show the 'type a custom value' row");
        assertFalse(hasTypeValueRow(builder.buildParamPicker(
                root, UUID.randomUUID(), p -> true, profile, List.of(), "world")),
                "world picker must not show the 'type a custom value' row");
        assertTrue(hasTypeValueRow(builder.buildParamPicker(
                root, UUID.randomUUID(), p -> true, profile, List.of(), "biome")),
                "non-destination pickers keep the 'type a custom value' fallback row");
    }

    @Test
    void regionValueRowsAreColorPrefixed() {
        TestRoot root = new TestRoot();
        root.getParameterLookup().put("region", enumParam("alpha", "beta"));

        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();
        MenuModel model = builder.buildParamPicker(root, UUID.randomUUID(),
                p -> true, MenuConsumerProfile.defaultProfile(), List.of(), "region");

        boolean sawAlpha = false;
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                String t = frag.text();
                if (t != null && t.endsWith("alpha")) {
                    sawAlpha = true;
                    assertTrue(t.matches("&.alpha"),
                            "region value row must carry a legacy color prefix, was: " + t);
                }
            }
        }
        assertTrue(sawAlpha, "region picker must list the 'alpha' value row");
    }

    private static boolean hasTypeValueRow(MenuModel model) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                String t = frag.text();
                if (t != null && t.contains("type a custom")) return true;
            }
        }
        return false;
    }

    private static CommandParameter enumParam(String... values) {
        Set<String> set = new LinkedHashSet<>(List.of(values));
        return new CommandParameter("", "desc", (u, v) -> true) {
            @Override
            public Set<String> values() {
                return set;
            }
        };
    }

    private static final class TestRoot extends BaseRTPCmdImpl {
        TestRoot() { super(null); }

        @Override public String name() { return "rtp"; }
        @Override public String permission() { return ""; }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) {
            return true;
        }
    }
}
