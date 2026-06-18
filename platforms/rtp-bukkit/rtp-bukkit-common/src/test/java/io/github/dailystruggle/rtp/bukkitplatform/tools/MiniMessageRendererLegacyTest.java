package io.github.dailystruggle.rtp.bukkitplatform.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the legacy-to-MiniMessage translation in {@link MiniMessageRenderer}
 * is tag-aware: it only rewrites legacy color/format codes that live in the
 * plain-text gaps between MiniMessage {@code <tag>} spans, never inside a tag's
 * own arguments. The recurring regression this guards against is a gradient's
 * {@code #rrggbb} color stops being rewritten to {@code <#rrggbb>}, which
 * corrupts the tag so MiniMessage can no longer parse it.
 */
class MiniMessageRendererLegacyTest {

  @Test
  @DisplayName("gradient tag arguments survive legacy translation untouched")
  void gradientArgsPreserved() {
    String in = "<gradient:#ff0000:#00ff00>Welcome</gradient>";
    assertEquals(in, MiniMessageRenderer.legacyToMiniMessage(in));
  }

  @Test
  @DisplayName("transition/rainbow tag arguments survive legacy translation untouched")
  void transitionArgsPreserved() {
    String in = "<transition:#5e4fa2:#f79459:0.5>Hi</transition>";
    assertEquals(in, MiniMessageRenderer.legacyToMiniMessage(in));
  }

  @Test
  @DisplayName("legacy codes outside tags are converted, gradient inside is preserved")
  void mixedLegacyOutsideGradientInside() {
    String in = "&c<gradient:#ff0000:#00ff00>x</gradient>&7y";
    String out = MiniMessageRenderer.legacyToMiniMessage(in);
    assertEquals("<red><gradient:#ff0000:#00ff00>x</gradient><gray>y", out);
  }

  @Test
  @DisplayName("bare #rrggbb in plain text (outside any tag) is still converted")
  void bareHexInPlainTextConverted() {
    assertEquals("<#abcdef>hello", MiniMessageRenderer.legacyToMiniMessage("#abcdefhello"));
  }

  @Test
  @DisplayName("bare #rrggbb inside a tag argument is NOT converted")
  void bareHexInsideTagNotConverted() {
    String in = "<hover:show_text:'<#abcdef>tip'>label";
    assertEquals(in, MiniMessageRenderer.legacyToMiniMessage(in));
  }

  @Test
  @DisplayName("a corrupted-before gradient now deserializes to a colored component")
  void gradientStillRendersAfterTranslation() {
    String translated =
        MiniMessageRenderer.legacyToMiniMessage("<gradient:#ff0000:#00ff00>Welcome</gradient>");
    Component component = MiniMessage.miniMessage().deserialize(translated);
    // A correctly-parsed gradient expands into per-character colored children.
    assertTrue(component.children().size() > 1
            || component.color() != null,
        "gradient should produce colored output, got: " + component);
  }
}
