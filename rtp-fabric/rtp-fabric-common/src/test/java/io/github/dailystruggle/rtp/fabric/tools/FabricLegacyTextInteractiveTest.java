package io.github.dailystruggle.rtp.fabric.tools;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link FabricLegacyText#parseInteractive(String, String, String)}
 * carries hover ({@code SHOW_TEXT}) and click ({@code SUGGEST_COMMAND}) annotations
 * on the root component, mirroring the rtp-spigot {@code SendMessage.sendMessage(
 * target, msg, hover, click)} path. Regression guard for the Fabric {@code /rtp info}
 * hover/click outage.
 */
class FabricLegacyTextInteractiveTest {

    /**
     * MC 1.21.1's {@code HoverEvent} static initializer touches
     * {@code BuiltInRegistries}, which throws "Not bootstrapped" unless
     * {@code Bootstrap.bootStrap()} has been called. Idempotent and cheap.
     */
    @BeforeAll
    static void bootstrapMinecraft() {
        // Required before Bootstrap.bootStrap(); otherwise FireBlock.bootStrap()
        // throws "Game version not set" via SharedConstants#getCurrentVersion.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void parseInteractive_nullHoverAndClick_returnsBareParseResult() {
        Component out = FabricLegacyText.parseInteractive("&ahello", null, null);
        assertNotNull(out, "parseInteractive must never return null");
        Style style = out.getStyle();
        assertNull(style.getHoverEvent(), "no hover should be applied when hover is null");
        assertNull(style.getClickEvent(), "no click should be applied when click is null");
    }

    @Test
    void parseInteractive_emptyHoverAndClick_returnsBareParseResult() {
        Component out = FabricLegacyText.parseInteractive("hello", "", "");
        Style style = out.getStyle();
        assertNull(style.getHoverEvent());
        assertNull(style.getClickEvent());
    }

    @Test
    void parseInteractive_attachesHoverEventShowText() {
        Component out = FabricLegacyText.parseInteractive("line", "&etip", null);
        HoverEvent hover = out.getStyle().getHoverEvent();
        assertNotNull(hover, "hover event must be attached when hover is non-empty");
        assertSame(HoverEvent.Action.SHOW_TEXT, hover.getAction(),
                "hover action must be SHOW_TEXT to match Bukkit/Paper semantics");
        Component hoverValue = hover.getValue(HoverEvent.Action.SHOW_TEXT);
        assertNotNull(hoverValue, "hover value must be a parsed Component");
    }

    @Test
    void parseInteractive_attachesClickEventSuggestCommand() {
        Component out = FabricLegacyText.parseInteractive("line", null, "/rtp info");
        ClickEvent click = out.getStyle().getClickEvent();
        assertNotNull(click, "click event must be attached when click is non-empty");
        assertSame(ClickEvent.Action.SUGGEST_COMMAND, click.getAction(),
                "click action must be SUGGEST_COMMAND to mirror BaseComponent ClickEvent");
        assertEquals("/rtp info", click.getValue(),
                "click value must be the literal command target");
    }

    @Test
    void parseInteractive_stripsColourFromClickTarget() {
        // Section codes inside a click target would leak into the player's
        // chat input; parseInteractive must strip them before attaching.
        Component out = FabricLegacyText.parseInteractive("line", null, "&a/rtp &binfo");
        ClickEvent click = out.getStyle().getClickEvent();
        assertNotNull(click);
        String value = click.getValue();
        assertFalse(value.contains("\u00A7"), "click target must not contain section signs");
        assertFalse(value.contains("&a"), "raw &-codes should be stripped from click target");
        assertTrue(value.endsWith("/rtp info") || value.contains("/rtp info"),
                "click target should preserve the literal command after colour strip: " + value);
    }

    @Test
    void parseInteractive_attachesBothHoverAndClick() {
        Component out = FabricLegacyText.parseInteractive("&6/rtp", "&7click to suggest", "/rtp");
        Style style = out.getStyle();
        assertNotNull(style.getHoverEvent(), "hover must be present");
        assertNotNull(style.getClickEvent(), "click must be present");
        assertSame(HoverEvent.Action.SHOW_TEXT, style.getHoverEvent().getAction());
        assertSame(ClickEvent.Action.SUGGEST_COMMAND, style.getClickEvent().getAction());
    }
}
